"""Backwards-compatible optional glossary configuration and schema checks."""

import builtins
from dataclasses import replace
from pathlib import Path
import runpy
import sys

import pytest
import yaml
from config import Config, LanguageConfig


BASE = {"modules": [{"name": "app", "res_path": "res"}],
        "languages": [{"code": "values", "name": "English", "is_source": True},
                      {"code": "values-zh", "name": "Chinese"}]}


def test_old_config_defaults_and_root(tmp_path):
    path = tmp_path / "config.yml"
    path.write_text(yaml.safe_dump(BASE))
    config = Config.load(path)
    assert config.batch_size == 10 and config.get_glossary("Chinese") == {}
    assert config.project_root == tmp_path.parent and config.get_source_language().code == "values"


def test_user_config_untouched_and_current_model_retained():
    path = Path(__file__).resolve().parents[1] / "config.yml"
    before = path.read_bytes()
    config = Config.load(path)
    assert config.translation_model == yaml.safe_load(before)["translation"]["model"]
    assert path.read_bytes() == before


@pytest.mark.parametrize("glossary", [None, [], "Prompt", {"Prompt": 1}, {1: "x"}, {"": "x"}, {"Prompt": " "}])
def test_invalid_glossary_rejected(glossary):
    with pytest.raises(ValueError, match="glossary"):
        LanguageConfig("values-zh", "Chinese", glossary=glossary)


@pytest.mark.parametrize("size", [0, -1, True, "10", 1.5])
def test_invalid_batch_size(config, size):
    with pytest.raises(ValueError, match="batch_size"):
        replace(config, batch_size=size)


@pytest.mark.parametrize("code", ["../values", "/tmp", "en", "values/../../", ""])
def test_invalid_directory(code):
    with pytest.raises(ValueError, match="directory"):
        LanguageConfig(code, "Bad")


@pytest.mark.parametrize("document", ["", "[]", "null", "string"])
def test_invalid_root_yaml(tmp_path, document):
    path = tmp_path / "config.yml"
    path.write_text(document)
    with pytest.raises(ValueError, match="mapping"):
        Config.load(path)


def test_duplicate_and_invalid_source(config):
    with pytest.raises(ValueError, match="Duplicate language"):
        replace(config, languages=config.languages + [config.languages[0]])
    with pytest.raises(ValueError, match="Source"):
        replace(config, languages=[LanguageConfig("values-ja", "Japanese", True)])


def test_live_module_guard_before_any_application_import(monkeypatch):
    monkeypatch.delenv("LOCALE_TUI_RUN_LIVE", raising=False)
    before = set(sys.modules)
    real_import = builtins.__import__
    def guarded_import(name, *args, **kwargs):
        if name in {"config", "openai", "services.translator", "pytest_asyncio"}:
            raise AssertionError(f"Live module imported {name} before opt-in guard")
        return real_import(name, *args, **kwargs)
    monkeypatch.setattr(builtins, "__import__", guarded_import)
    with pytest.raises(pytest.skip.Exception, match="explicit"):
        runpy.run_path(str(Path(__file__).with_name("test_translator.py")))
    assert not {"config", "openai", "services.translator"} & (set(sys.modules) - before)


def test_optional_glossary_yaml_is_loaded_without_template_replacement(tmp_path):
    data = {**BASE, "translation": {"model": "user-model", "batch_size": 4,
                                   "prompt_template": "USER {target_language} {source_strings}"}}
    data["languages"] = [*BASE["languages"], {"code": "values-fr", "name": "French", "glossary": {"Prompt": "invite"}}]
    path = tmp_path / "config.yml"
    path.write_text(yaml.safe_dump(data))
    before = path.read_bytes()
    config = Config.load(path)
    assert config.get_glossary("values-fr") == {"Prompt": "invite"}
    assert config.translation_model == "user-model" and config.batch_size == 4
    assert config.translation_prompt.startswith("USER ") and path.read_bytes() == before


def test_invalid_source_boolean():
    with pytest.raises(ValueError, match="boolean"):
        LanguageConfig("values", "English", is_source="false")
