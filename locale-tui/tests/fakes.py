"""In-memory OpenAI-shaped API; no transport or SDK client is created."""

import asyncio
import inspect
import json
from types import SimpleNamespace


def response(content, *, choices=True):
    return SimpleNamespace(choices=[SimpleNamespace(message=SimpleNamespace(content=content))] if choices else [])


class FakeClient:
    def __init__(self, handler=None, results=None):
        self.chat = SimpleNamespace(completions=self)
        self.handler = handler
        self.results = list(results or [])
        self.calls = []
        self.closed = False
        self.active = 0
        self.max_active = 0
        self.cancelled = 0

    async def create(self, **kwargs):
        self.calls.append(kwargs)
        self.active += 1
        self.max_active = max(self.active, self.max_active)
        try:
            prompt = kwargs["messages"][0]["content"]
            entries = json.JSONDecoder().raw_decode(prompt[prompt.index("{"):])[0] if "{" in prompt else {}
            if self.handler:
                value = self.handler(entries, kwargs)
                if inspect.isawaitable(value):
                    value = await value
            else:
                value = self.results.pop(0) if self.results else {key: text + " translated" for key, text in entries.items()}
            if isinstance(value, Exception):
                raise value
            if isinstance(value, SimpleNamespace):
                return value
            return response(value if isinstance(value, str) else json.dumps(value, ensure_ascii=False))
        except asyncio.CancelledError:
            self.cancelled += 1
            raise
        finally:
            self.active -= 1

    async def close(self):
        self.closed = True
