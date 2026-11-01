"""Offline test setup: no socket connections, real credentials or user file writes."""

import os
from pathlib import Path
import socket
import sys

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))


@pytest.fixture(autouse=True)
def offline_guard(monkeypatch):
    if os.environ.get("LOCALE_TUI_RUN_LIVE") == "1":
        return
    def deny(*args, **kwargs):
        raise AssertionError("Network forbidden in offline tests")
    monkeypatch.setattr(socket.socket, "connect", deny)
    monkeypatch.setattr(socket, "create_connection", deny)
    monkeypatch.setattr(socket, "getaddrinfo", deny)
    monkeypatch.setenv("OPENAI_API_KEY", "offline-fake-key")
    monkeypatch.setenv("OPENAI_BASE_URL", "https://offline.invalid/v1")


@pytest.fixture
def config(tmp_path):
    from config import Config, LanguageConfig, ModuleConfig
    return Config(
        openai_api_key="fake", openai_base_url="https://offline.invalid/v1",
        project_root=tmp_path, modules=[ModuleConfig("app", "res")],
        languages=[LanguageConfig("values", "English", True),
                   LanguageConfig("values-zh", "Chinese", glossary={"Prompt": "提示词"}),
                   LanguageConfig("values-ja", "Japanese")],
        translation_model="fake-model", translation_prompt="Translate to {target_language}:\n{source_strings}",
        batch_size=2, column_widths={}, page_size=50,
    )
