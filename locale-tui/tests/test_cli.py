"""Actual Click command workflows with in-memory API and temporary resources."""

import asyncio

import pytest
from click.testing import CliRunner
from config import ModuleConfig
import main
from services.translator import AITranslator
from services.xml_parser import StringsXmlParser as XML
from fakes import FakeClient, response


@pytest.fixture
def resources(config):
    source = config.project_root / "res/values/strings.xml"
    source.parent.mkdir(parents=True)
    source.write_text('''<resources>
  <string name="a">Prompt</string><string name="b">Hello %s</string>
  <string name="c">Existing</string><string name="brand" translatable="false">Brand</string>
</resources>''')
    target = config.project_root / "res/values-zh/strings.xml"
    target.parent.mkdir(parents=True)
    target.write_text('''<resources><!--keep--><string name="c" formatted="false">Old</string>
<plurals name="p"><item quantity="other">Many</item></plurals><string-array name="arr"><item>X</item></string-array>
<string name="extra">User data <b>span</b> tail</string></resources>''')
    return source, target


@pytest.fixture
def wired(config, monkeypatch):
    monkeypatch.setattr(main, "load_config", lambda: config)
    def install(client):
        monkeypatch.setattr(main, "AITranslator", lambda c: AITranslator(c, client=client))
        return CliRunner()
    return install


def test_missing_patches_only_selected_and_closes(config, resources, wired):
    source, target = resources
    before_source = source.read_bytes()
    client = FakeClient()
    result = wired(client).invoke(main.cli, ["translate-missing", "-l", "values-zh", "-m", "app", "--concurrency", "2"])
    assert result.exit_code == 0, result.output
    entries = XML.parse(target)
    assert entries["c"] == "Old" and entries["extra"] == "User data <b>span</b> tail"
    assert entries["a"] == "Prompt translated" and entries["b"] == "Hello %s translated"
    assert "brand" not in entries and source.read_bytes() == before_source
    assert "plurals" in target.read_text() and "<!--keep-->" in target.read_text() and 'formatted="false"' in target.read_text()
    assert client.closed and '"Prompt": "提示词"' in client.calls[0]["messages"][0]["content"]


def test_blank_is_missing(config, resources, wired):
    _, target = resources
    XML.update_entry(target, "a", " ")
    result = wired(FakeClient()).invoke(main.cli, ["translate-missing", "-l", "values-zh"])
    assert result.exit_code == 0 and XML.parse(target)["a"] == "Prompt translated"


def test_dry_run_never_constructs_client_or_writes(resources, wired):
    _, target = resources
    before = target.read_bytes()
    runner = wired(FakeClient())
    def no_client(*args):
        raise AssertionError("dry-run constructed client")
    main.AITranslator = no_client
    result = runner.invoke(main.cli, ["translate-missing", "--dry-run", "-l", "values-zh"])
    assert result.exit_code == 0 and "缺失 2 条" in result.output and target.read_bytes() == before


def test_retranslate_union_existing_only(config, resources, wired):
    _, target = resources
    XML.update_entries(target, {"a": "Old Prompt", "b": "old %s"})
    client = FakeClient()
    result = wired(client).invoke(main.cli, ["retranslate", "-l", "values-zh", "--key", "a", "--match", "Existing"])
    assert result.exit_code == 0, result.output
    entries = XML.parse(target)
    assert entries["a"] == "Prompt translated" and entries["c"] == "Existing translated"
    assert entries["b"] == "old %s" and entries["extra"] == "User data <b>span</b> tail"


def test_retranslate_does_not_fill_missing(resources, wired):
    _, target = resources
    result = wired(FakeClient()).invoke(main.cli, ["retranslate", "-l", "values-zh", "--key", "a"])
    assert result.exit_code == 0 and "a" not in XML.parse(target)


def test_failure_preserves_old_value_and_saves_valid_subset(resources, wired):
    _, target = resources
    XML.update_entries(target, {"a": "old A", "b": "old %s"})
    client = FakeClient(handler=lambda entries, kwargs: {k: "new" for k in entries})
    result = wired(client).invoke(main.cli, ["retranslate", "-l", "values-zh", "--key", "a", "--key", "b", "--retries", "2"])
    assert result.exit_code == 1 and "placeholder" in result.output
    assert XML.parse(target)["a"] == "new" and XML.parse(target)["b"] == "old %s"
    assert client.closed


@pytest.mark.parametrize("args", [
    ["translate-missing", "--concurrency", "0"], ["translate-missing", "--retries", "-1"],
    ["translate-missing", "-l", "../../bad"], ["translate-missing", "-m", "unknown"],
    ["retranslate"], ["retranslate", "--match", "["],
])
def test_invalid_options_never_request(resources, wired, args):
    client = FakeClient()
    result = wired(client).invoke(main.cli, args)
    assert result.exit_code != 0 and not client.calls


def test_target_nontranslatable_not_overwritten(resources, wired):
    _, target = resources
    target.write_text('<resources><string name="a" translatable="false">Protected</string></resources>')
    client = FakeClient()
    result = wired(client).invoke(main.cli, ["retranslate", "-l", "values-zh", "--key", "a"])
    assert result.exit_code == 0 and XML.parse(target)["a"] == "Protected" and not client.calls
    result = wired(client).invoke(main.cli, ["translate-missing", "-l", "values-zh"])
    assert result.exit_code == 0 and XML.parse(target)["a"] == "Protected"


@pytest.mark.parametrize("which", ["source", "target"])
def test_file_changed_during_translation_never_overwritten(resources, wired, which):
    source, target = resources
    changed = source if which == "source" else target
    original_target = target.read_bytes()
    def handler(entries, kwargs):
        changed.write_text(changed.read_text().replace("Prompt", "Changed Prompt") if which == "source" else '<resources><string name="user">External</string></resources>')
        return entries
    client = FakeClient(handler)
    result = wired(client).invoke(main.cli, ["translate-missing", "-l", "values-zh"])
    assert result.exit_code == 1 and client.closed
    if which == "source":
        assert target.read_bytes() == original_target and "Changed Prompt" in source.read_text()
    else:
        assert XML.parse(target) == {"user": "External"}


def test_malformed_target_fail_safe(resources, wired):
    _, target = resources
    target.write_text("<resources><broken")
    client = FakeClient()
    result = wired(client).invoke(main.cli, ["translate-missing", "-l", "values-zh"])
    assert result.exit_code == 1 and not client.calls and client.closed
    assert target.read_text() == "<resources><broken"


def test_cancel_current_file_no_save_client_closed(resources, wired):
    _, target = resources
    before = target.read_bytes()
    async def handler(entries, kwargs):
        raise asyncio.CancelledError()
    client = FakeClient(handler)
    result = wired(client).invoke(main.cli, ["translate-missing", "-l", "values-zh"])
    assert result.exit_code == 1 and "已取消" in result.output and "0 个文件" in result.output
    assert target.read_bytes() == before and client.closed and client.active == 0


def test_cancel_after_prior_language_saved_reports_partial(config, resources, wired):
    async def handler(entries, kwargs):
        if "Japanese" in kwargs["messages"][0]["content"]:
            raise asyncio.CancelledError()
        return entries
    client = FakeClient(handler)
    result = wired(client).invoke(main.cli, ["translate-missing"])
    assert result.exit_code == 1 and "1 个文件" in result.output and "完成！" not in result.output
    assert XML.parse(resources[1])["a"] == "Prompt"
    assert not (config.project_root / "res/values-ja/strings.xml").exists()
    assert client.closed and client.active == 0


def test_multiple_modules(config, resources, wired):
    config.modules.append(ModuleConfig("second", "second"))
    XML.write(config.project_root / "second/values/strings.xml", {"x": "X"})
    result = wired(FakeClient()).invoke(main.cli, ["translate-missing", "-m", "app", "-m", "second", "-l", "values-zh"])
    assert result.exit_code == 0
    assert XML.parse(config.project_root / "second/values-zh/strings.xml") == {"x": "X translated"}


@pytest.mark.parametrize("result_value,expected", [(response("OK"), 0), (RuntimeError("offline down"), 1)])
def test_existing_connection_command_closes(wired, result_value, expected):
    client = FakeClient(results=[result_value])
    result = wired(client).invoke(main.cli, ["test-connection"])
    assert result.exit_code == expected and client.closed


@pytest.mark.parametrize("valid", [True, False])
def test_existing_add_uses_validator_and_closes(resources, wired, valid):
    _, target = resources
    client = FakeClient(handler=lambda entries, kwargs: {key: "好 %s" if valid else "bad" for key in entries})
    result = wired(client).invoke(main.cli, ["add", "new", "Hello %s"])
    assert result.exit_code == (0 if valid else 1), result.output
    assert client.closed
    assert ("new" in XML.parse(target)) is valid
    assert XML.parse(resources[0])["new"] == "Hello %s"


def test_existing_offline_commands(resources, wired):
    client = FakeClient()
    runner = wired(client)
    assert runner.invoke(main.cli, ["add", "new", "New", "--skip-translate"]).exit_code == 0
    assert runner.invoke(main.cli, ["set", "new", "Manual", "-l", "values-zh"]).exit_code == 0
    assert XML.parse(resources[1])["new"] == "Manual"
    assert runner.invoke(main.cli, ["list-keys"]).exit_code == 0 and not client.calls


def test_add_cancel_drains_language_tasks_and_closes(resources, wired):
    async def handler(entries, kwargs):
        if "Chinese" in kwargs["messages"][0]["content"]:
            await asyncio.sleep(0)
            raise asyncio.CancelledError()
        await asyncio.Event().wait()
    client = FakeClient(handler)
    result = wired(client).invoke(main.cli, ["add", "cancelled", "Cancelled"])
    assert result.exit_code == 1 and "已取消" in result.output and "完成！" not in result.output
    assert client.closed and client.active == 0
    assert XML.parse(resources[0])["cancelled"] == "Cancelled"
    assert "cancelled" not in XML.parse(resources[1])
