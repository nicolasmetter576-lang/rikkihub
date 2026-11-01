"""AI translation service with bounded batches and offline result validation."""

import asyncio
import json
import re
from typing import Optional, Callable, TYPE_CHECKING

from openai import AsyncOpenAI
from services.validation import escape_android_quotes, validate_translation

if TYPE_CHECKING:
    from config import Config
    from models.entry import TranslationEntry


class TranslationError(Exception):
    """Translation request or response error."""


class AITranslator:
    """Shared CLI/TUI translation engine; no disk writes, no background orphan tasks."""

    def __init__(self, config: "Config", *, client=None):
        self.config = config
        if client is None and not config.openai_api_key:
            raise TranslationError("OPENAI_API_KEY is required for translation")
        self.client = client if client is not None else AsyncOpenAI(
            api_key=config.openai_api_key, base_url=config.openai_base_url,
            max_retries=0, timeout=60,
        )
        self.last_failures: dict[str, dict[str, str]] = {}

    async def close(self) -> None:
        await self.client.close()

    async def test_connection(self) -> str | None:
        """Explicitly requested live connection check (never part of offline tests)."""
        try:
            response = await self.client.chat.completions.create(
                model=self.config.translation_model,
                messages=[{"role": "user", "content": "Reply exactly with OK to confirm the connection."}],
                temperature=0, max_tokens=16,
            )
            if not response.choices:
                raise TranslationError("No choices in API response")
            content = response.choices[0].message.content
            return content.strip() if content else None
        except Exception as exc:
            raise TranslationError(f"Connection test failed: {exc}") from exc

    def build_prompt(self, entries: dict[str, str], target_language: str) -> str:
        """Keep the user's template; append optional language-specific terminology."""
        try:
            prompt = self.config.translation_prompt.format(
                target_language=self.config.get_language_name(target_language),
                source_strings=json.dumps(entries, ensure_ascii=False, indent=2),
            )
        except (KeyError, ValueError, IndexError) as exc:
            raise TranslationError(f"Invalid translation prompt template: {exc}") from exc
        glossary = self.config.get_glossary(target_language)
        if glossary:
            prompt += ("\n\nMandatory terminology (inflect only as needed for grammar):\n" +
                       json.dumps(glossary, ensure_ascii=False, indent=2))
        return prompt

    async def translate_batch(self, entries: dict[str, str], target_language: str) -> dict[str, str]:
        """One API attempt. Do not apply unvalidated results to memory or files."""
        prompt = self.build_prompt(entries, target_language)
        try:
            response = await self.client.chat.completions.create(
                model=self.config.translation_model,
                messages=[{"role": "user", "content": prompt}], temperature=1.0,
            )
            if not response.choices or not response.choices[0].message.content:
                raise TranslationError("Empty response from API")
            content = response.choices[0].message.content.strip()
            fence = re.fullmatch(r"```(?:json)?\s*\n(.*?)\n```", content, re.DOTALL)
            if fence:
                content = fence.group(1)
            def unique_object(pairs):
                result = {}
                for key, value in pairs:
                    if key in result:
                        raise ValueError(f"Duplicate response key: {key}")
                    result[key] = value
                return result
            result = json.loads(content, object_pairs_hook=unique_object)
            if not isinstance(result, dict):
                raise TranslationError("Response must be a JSON object")
            if set(result) - set(entries):
                raise TranslationError("Unexpected response keys")
            return result
        except Exception as exc:
            raise TranslationError(f"Translation failed: {exc}") from exc

    async def translate_entries(
        self, entries: dict[str, str], target_language: str, concurrency: int = 8,
        retries: int = 3, progress_callback: Optional[Callable[[int, int], None]] = None,
    ) -> tuple[dict[str, str], dict[str, str]]:
        """Return (validated translations, exhausted failures).

        retries is the maximum total attempts. Only failed keys are retried.
        Cancellation propagates and drains all workers; this call returns no partial
        result on cancellation. Progress counts completed keys, including failures.
        """
        for name, value in (("concurrency", concurrency), ("retries", retries), ("batch_size", self.config.batch_size)):
            if type(value) is not int or value < 1:
                raise ValueError(f"{name} must be a positive integer")
        if any(not isinstance(k, str) or not isinstance(v, str) for k, v in entries.items()):
            raise ValueError("Entries must map string keys to source strings")
        translations, failures = {}, {}
        keys = list(entries)
        batches = iter([keys[i:i + self.config.batch_size] for i in range(0, len(keys), self.config.batch_size)])
        done = 0
        if progress_callback:
            progress_callback(0, len(keys))

        async def worker():
            nonlocal done
            for batch_keys in batches:
                pending = {key: entries[key] for key in batch_keys}
                for _ in range(retries):
                    if not pending:
                        break
                    try:
                        result = await self.translate_batch(pending, target_language)
                    except TranslationError as exc:
                        failures.update({key: str(exc) for key in pending})
                        continue
                    for key in list(pending):
                        value = result.get(key)
                        if not isinstance(value, str):
                            failures[key] = "missing or non-string response value"
                            continue
                        value = escape_android_quotes(value)
                        error = validate_translation(pending[key], value)
                        if error:
                            failures[key] = error
                            continue
                        translations[key] = value
                        failures.pop(key, None)
                        del pending[key]
                done += len(batch_keys)
                if progress_callback:
                    progress_callback(done, len(keys))

        tasks = [asyncio.create_task(worker()) for _ in range(min(concurrency, (len(keys) + self.config.batch_size - 1) // self.config.batch_size))]
        try:
            await asyncio.gather(*tasks)
        finally:
            for task in tasks:
                if not task.done():
                    task.cancel()
            await asyncio.gather(*tasks, return_exceptions=True)
        return translations, failures

    async def translate_all_missing(
        self, entries: list["TranslationEntry"], target_languages: list[str],
        progress_callback: Optional[Callable[[str, int, int, str], None]] = None,
        *, retranslate: bool = False,
    ) -> int:
        """Stage validated values in memory, never save. Retranslate only existing values.

        Each completed language is staged. A cancelled language is not applied;
        earlier completed languages remain staged for explicit user save/review.
        """
        self.last_failures = {}
        total_translated = 0
        for code in dict.fromkeys(target_languages):
            if code == "values":
                continue
            if code not in self.config.get_language_codes():
                raise ValueError(f"Unknown target language: {code}")
            selected = {e.key: e.get_translation("values") for e in entries
                        if e.is_translatable and code not in e.excluded_languages and (e.get_translation("values") or "").strip()
                        and (bool((e.get_translation(code) or "").strip()) if retranslate
                             else not (e.get_translation(code) or "").strip())}
            if not selected:
                continue
            def progress(done, total, code=code):
                if progress_callback:
                    progress_callback(code, done, total, f"{code}: {done}/{total} completed")
            result, failures = await self.translate_entries(selected, code, progress_callback=progress)
            self.last_failures[code] = failures
            for entry in entries:
                if entry.key in result:
                    entry.set_translation(code, result[entry.key])
                    total_translated += 1
        return total_translated
