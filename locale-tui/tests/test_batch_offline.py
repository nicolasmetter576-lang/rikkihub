"""Fake API tests for bounded batching, retries, terminology and cancellation."""

import asyncio
import json

import pytest
from config import LanguageConfig
from models.entry import TranslationEntry
from services.translator import AITranslator, TranslationError
from fakes import FakeClient, response


def test_glossary_optional_and_language_resolution(config):
    service = AITranslator(config, client=FakeClient())
    prompt = service.build_prompt({"x": "Prompt"}, "values-zh")
    assert "Chinese" in prompt and '"Prompt": "提示词"' in prompt
    assert service.build_prompt({"x": "Prompt"}, "Chinese") == prompt
    assert "Mandatory terminology" not in service.build_prompt({"x": "Prompt"}, "values-ja")
    config.languages.append(LanguageConfig("values-fr", "Chinese", glossary={"Prompt": "autre"}))
    assert "autre" in service.build_prompt({"x": "Prompt"}, "values-fr")
    assert "提示词" in service.build_prompt({"x": "Prompt"}, "values-zh")


@pytest.mark.parametrize("prompt", ["{unknown}", "{", "{source_strings!bad}"])
async def test_bad_template_no_request(config, prompt):
    config.translation_prompt = prompt
    client = FakeClient()
    with pytest.raises(TranslationError, match="prompt"):
        await AITranslator(config, client=client).translate_batch({"x": "X"}, "Chinese")
    assert not client.calls


@pytest.mark.parametrize("bad", ["not json", "[]", "42", '{"x":"a","x":"b"}', {"extra": "bad"}, response(None), response("{}", choices=False)])
async def test_bad_response_is_reported(config, bad):
    client = FakeClient(results=[bad, bad])
    result, failures = await AITranslator(config, client=client).translate_entries({"x": "X"}, "Chinese", retries=2)
    assert not result and set(failures) == {"x"} and len(client.calls) == 2


async def test_fenced_json_and_quote_escaping(config):
    client = FakeClient(results=['```json\n{"x": "don\'t"}\n```'])
    result, failures = await AITranslator(config, client=client).translate_entries({"x": "Don't"}, "Chinese")
    assert result == {"x": "don\\'t"} and not failures


async def test_retry_only_failed_entries_and_progress(config):
    client = FakeClient(results=[{"a": "好 %1$s", "b": "lost placeholder"}, {"b": "保留 %d"}])
    progress = []
    result, failures = await AITranslator(config, client=client).translate_entries(
        {"a": "Hello %1$s", "b": "Count %d"}, "values-zh", progress_callback=lambda d, t: progress.append((d, t)))
    assert result == {"a": "好 %1$s", "b": "保留 %d"} and not failures
    prompt = client.calls[1]["messages"][0]["content"]
    assert '"a"' not in prompt and '"b"' in prompt
    assert progress == [(0, 2), (2, 2)]


async def test_validation_failures_not_returned(config):
    client = FakeClient(results=[{"a": "", "b": None}, {"a": " ", "b": 42}])
    result, failures = await AITranslator(config, client=client).translate_entries({"a": "A", "b": "B"}, "Chinese", retries=2)
    assert not result and set(failures) == {"a", "b"}


async def test_transport_error_then_success(config):
    client = FakeClient(results=[RuntimeError("offline simulated outage"), {"x": "好"}])
    result, failures = await AITranslator(config, client=client).translate_entries({"x": "X"}, "Chinese", retries=2)
    assert result == {"x": "好"} and not failures


async def test_concurrency_bound_batch_sizes_progress(config):
    async def handler(entries, kwargs):
        await asyncio.sleep(0.005)
        return {k: v + " OK" for k, v in entries.items()}
    client = FakeClient(handler)
    progress = []
    source = {f"k{i}": f"Text {i}" for i in range(31)}
    result, failures = await AITranslator(config, client=client).translate_entries(
        source, "Chinese", concurrency=3, progress_callback=lambda d, t: progress.append((d, t)))
    assert set(result) == set(source) and not failures
    assert len(client.calls) == 16 and client.max_active == 3 and client.active == 0
    sizes = [len(json.JSONDecoder().raw_decode(c["messages"][0]["content"].split("\n", 1)[1])[0]) for c in client.calls]
    assert all(n <= 2 for n in sizes)
    assert progress[-1] == (31, 31)
    assert [p[0] for p in progress] == sorted(p[0] for p in progress)


@pytest.mark.parametrize("param,value", [("concurrency", 0), ("concurrency", -1), ("retries", 0), ("retries", True), ("batch_size", 0)])
async def test_invalid_limits_fail_before_api(config, param, value):
    client = FakeClient()
    options = {param: value}
    if param == "batch_size":
        config.batch_size = value
        options = {}
    with pytest.raises(ValueError):
        await AITranslator(config, client=client).translate_entries({"x": "X"}, "Chinese", **options)
    assert not client.calls


async def test_empty_input_no_api(config):
    client = FakeClient()
    assert await AITranslator(config, client=client).translate_entries({}, "Chinese") == ({}, {})
    assert not client.calls


async def test_cancel_drains_workers_and_stages_nothing(config):
    started = asyncio.Event()
    async def handler(entries, kwargs):
        started.set()
        await asyncio.Event().wait()
    client = FakeClient(handler)
    service = AITranslator(config, client=client)
    baseline_tasks = set(asyncio.all_tasks())
    task = asyncio.create_task(service.translate_entries({str(i): "X" for i in range(30)}, "Chinese", concurrency=3))
    await started.wait()
    await asyncio.sleep(0)
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    assert client.active == 0 and client.cancelled == 3
    assert not (set(asyncio.all_tasks()) - baseline_tasks)


async def test_progress_error_drains_other_workers(config):
    async def handler(entries, kwargs):
        if "a" not in entries:
            await asyncio.sleep(30)
        return entries
    client = FakeClient(handler)
    def progress(done, total):
        if done:
            raise RuntimeError("callback failed")
    with pytest.raises(RuntimeError, match="callback"):
        await AITranslator(config, client=client).translate_entries({"a": "A", "b": "B", "c": "C"}, "Chinese", progress_callback=progress)
    assert client.active == 0


async def test_missing_skips_existing_source_disabled_and_duplicates(config):
    entries = [TranslationEntry("a", {"values": "A", "values-zh": "already"}),
               TranslationEntry("b", {"values": "B"}),
               TranslationEntry("c", {"values": "C"}, is_translatable=False),
               TranslationEntry("d", {"values-zh": "no source"})]
    client = FakeClient()
    service = AITranslator(config, client=client)
    count = await service.translate_all_missing(entries, ["values", "values-zh", "values-zh"])
    assert count == 1 and entries[0].get_translation("values-zh") == "already"
    assert entries[1].get_translation("values-zh") == "B translated"
    assert not entries[2].get_translation("values-zh") and len(client.calls) == 1


async def test_retranslation_only_existing_and_failed_value_preserved(config):
    entries = [TranslationEntry("a", {"values": "A", "values-zh": "old"}),
               TranslationEntry("b", {"values": "B %s", "values-zh": "old %s"}),
               TranslationEntry("c", {"values": "C"})]
    client = FakeClient(handler=lambda entries, kwargs: {k: "new" for k in entries})
    service = AITranslator(config, client=client)
    assert await service.translate_all_missing(entries, ["values-zh"], retranslate=True) == 1
    assert entries[0].get_translation("values-zh") == "new"
    assert entries[1].get_translation("values-zh") == "old %s"
    assert entries[2].get_translation("values-zh") is None
    assert set(service.last_failures["values-zh"]) == {"b"}


async def test_cancel_later_language_retains_earlier_staged_only(config):
    started = asyncio.Event()
    async def handler(entries, kwargs):
        if "Japanese" in kwargs["messages"][0]["content"]:
            started.set()
            await asyncio.Event().wait()
        return {k: "new" for k in entries}
    client = FakeClient(handler)
    entry = TranslationEntry("a", {"values": "A"})
    service = AITranslator(config, client=client)
    task = asyncio.create_task(service.translate_all_missing([entry], ["values-zh", "values-ja"]))
    await started.wait()
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    assert entry.get_translation("values-zh") == "new" and entry.get_translation("values-ja") is None
    assert client.active == 0


async def test_cancel_after_completed_batch_stages_no_current_language(config):
    config.batch_size = 1
    started = asyncio.Event()
    async def handler(entries, kwargs):
        if "a" in entries:
            return {"a": "valid"}
        started.set()
        await asyncio.Event().wait()
    client = FakeClient(handler)
    entries = [TranslationEntry("a", {"values": "A"}), TranslationEntry("b", {"values": "B"})]
    service = AITranslator(config, client=client)
    task = asyncio.create_task(service.translate_all_missing(entries, ["values-zh"]))
    await started.wait()
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    assert all(e.get_translation("values-zh") is None for e in entries)
    assert client.active == 0


async def test_whitespace_missing_and_empty_source_skipped(config):
    entries = [TranslationEntry("a", {"values": "A", "values-zh": " "}),
               TranslationEntry("b", {"values": " "})]
    client = FakeClient()
    assert entries[0].has_missing_translations(["values-zh"])
    assert not entries[1].has_missing_translations(["values-zh"])
    count = await AITranslator(config, client=client).translate_all_missing(entries, ["values-zh"])
    assert count == 1 and entries[0].get_translation("values-zh") == "A translated"
    assert entries[1].get_translation("values-zh") is None


def test_no_api_key_fails_without_client(config):
    config.openai_api_key = ""
    with pytest.raises(TranslationError, match="OPENAI_API_KEY"):
        AITranslator(config)
