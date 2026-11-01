"""Real Textual pilot workflows: staging, confirmation, cancel, conflicts, saves."""

import asyncio

import pytest
from textual.widgets import DataTable, Input, ProgressBar
from textual.worker import WorkerCancelled
from app import LocaleTuiApp
from screens.translation_table import TranslationTableScreen
import screens.translation_table as table_module
from services.translator import AITranslator
from services.xml_parser import StringsXmlParser as XML
from widgets.confirm_modal import ConfirmModal
from widgets.edit_modal import EditModal
from fakes import FakeClient


@pytest.fixture
def resources(config):
    source = config.project_root / "res/values/strings.xml"
    source.parent.mkdir(parents=True)
    source.write_text('<resources><string name="a">Prompt</string><string name="b">Count %d</string><string name="brand" translatable="false">Brand</string></resources>')
    target = config.project_root / "res/values-zh/strings.xml"
    target.parent.mkdir(parents=True)
    target.write_text('<resources><!--keep--><string name="b" formatted="false">Old %d</string><string-array name="arr"><item>X</item></string-array></resources>')
    return source, target


@pytest.fixture
def wired(config, monkeypatch):
    def install(client):
        monkeypatch.setattr(table_module, "AITranslator", lambda c: AITranslator(c, client=client))
        app = LocaleTuiApp(config)
        messages = []
        monkeypatch.setattr(app, "notify", lambda message, **kwargs: messages.append(str(message)))
        return app, messages
    return install


async def open_table(pilot, app):
    await pilot.press("enter")
    await pilot.pause()
    assert isinstance(app.screen, TranslationTableScreen)
    return app.screen


async def settle(app, pilot):
    try:
        await asyncio.wait_for(app.workers.wait_for_complete(), timeout=3)
    except WorkerCancelled:
        pass  # Expected outcome after c/Escape, not an application failure.
    await pilot.pause()


async def test_translate_stage_save_actual_keys_preserves_resources(resources, wired):
    source, target = resources
    before = target.read_bytes()
    original_source = source.read_bytes()
    client = FakeClient()
    app, messages = wired(client)
    async with app.run_test(size=(140, 45)) as pilot:
        screen = await open_table(pilot, app)
        await pilot.press("t")
        await settle(app, pilot)
        assert screen.has_unsaved_changes and client.closed
        assert target.read_bytes() == before and source.read_bytes() == original_source
        assert any("Staged" in message for message in messages)
        assert not screen.query_one("#progress", ProgressBar).display
        await pilot.press("s")
        assert not screen.has_unsaved_changes
        entries = XML.parse(target)
        assert entries["a"] == "Prompt translated" and entries["b"] == "Old %d" and "brand" not in entries
        assert "string-array" in target.read_text() and "<!--keep-->" in target.read_text() and 'formatted="false"' in target.read_text()
        assert source.read_bytes() == original_source


async def test_selected_retranslate_requires_confirmation_and_only_existing(resources, wired):
    client = FakeClient()
    app, messages = wired(client)
    async with app.run_test(size=(140, 45)) as pilot:
        screen = await open_table(pilot, app)
        screen.query_one("#table", DataTable).move_cursor(row=1)
        await pilot.press("ctrl+t")
        assert isinstance(app.screen, ConfirmModal)
        await pilot.click("#cancel")
        assert not client.calls
        await pilot.press("ctrl+t")
        await pilot.click("#confirm")
        await settle(app, pilot)
        assert screen.entries[1].get_translation("values-zh") == "Count %d translated"
        assert screen.entries[1].get_translation("values-ja") is None
        assert screen.entries[0].get_translation("values-zh") is None
        assert client.closed and XML.parse(resources[1])["b"] == "Old %d"


async def test_target_nontranslatable_tui_retranslate_and_missing_skip(resources, wired):
    source, target = resources
    target.write_text('<resources><string name="a" translatable="false">Protected</string><string name="b">Old %d</string></resources>')
    client = FakeClient()
    app, messages = wired(client)
    async with app.run_test(size=(140, 45)) as pilot:
        screen = await open_table(pilot, app)
        assert "values-zh" in screen.entries[0].excluded_languages
        await pilot.press("ctrl+t")
        await pilot.click("#confirm")
        await settle(app, pilot)
        assert not client.calls and screen.entries[0].get_translation("values-zh") == "Protected"
        await pilot.press("t")
        await settle(app, pilot)
        assert screen.entries[0].get_translation("values-zh") == "Protected"
        await pilot.press("s")
        assert XML.parse(target)["a"] == "Protected"
        assert 'translatable="false"' in target.read_text()


async def test_cancel_current_language_no_tasks_no_disk_or_dirty(resources, wired):
    started = asyncio.Event()
    async def handler(entries, kwargs):
        started.set()
        await asyncio.Event().wait()
    client = FakeClient(handler)
    app, messages = wired(client)
    before = resources[1].read_bytes()
    async with app.run_test(size=(140, 45)) as pilot:
        screen = await open_table(pilot, app)
        tasks_before = set(asyncio.all_tasks())
        await pilot.press("t")
        await asyncio.wait_for(started.wait(), 3)
        assert screen.translation_running
        await pilot.press("s")
        assert resources[1].read_bytes() == before
        await pilot.press("c")
        await settle(app, pilot)
        assert not screen.translation_running and not screen.has_unsaved_changes
        assert client.closed and client.active == 0
        assert any("Cancelled" in message for message in messages)
        assert not any("Staged" in message for message in messages)
        # Textual may recreate internal timers; no engine worker may survive.
        assert not [t for t in asyncio.all_tasks() - tasks_before if "AITranslator" in repr(t.get_coro())]


async def test_cancel_later_language_retains_unsaved_earlier(resources, wired):
    started = asyncio.Event()
    async def handler(entries, kwargs):
        if "Japanese" in kwargs["messages"][0]["content"]:
            started.set()
            await asyncio.Event().wait()
        return {k: v + " translated" for k, v in entries.items()}
    client = FakeClient(handler)
    app, messages = wired(client)
    before = resources[1].read_bytes()
    async with app.run_test(size=(140, 45)) as pilot:
        screen = await open_table(pilot, app)
        await pilot.press("t")
        await asyncio.wait_for(started.wait(), 3)
        await pilot.press("escape")  # cancels, does not navigate/discard
        await settle(app, pilot)
        assert app.screen is screen and screen.has_unsaved_changes
        assert screen.entries[0].get_translation("values-zh") == "Prompt translated"
        assert screen.entries[0].get_translation("values-ja") is None
        assert resources[1].read_bytes() == before and client.closed and client.active == 0
        assert any("Earlier completed languages remain staged" in message for message in messages)
        await pilot.press("s")
        assert XML.parse(resources[1])["a"] == "Prompt translated"


@pytest.mark.parametrize("which", ["source", "target"])
async def test_saved_fingerprint_conflict_refuses_all_tui_writes(resources, wired, which):
    client = FakeClient()
    app, messages = wired(client)
    async with app.run_test(size=(140, 45)) as pilot:
        screen = await open_table(pilot, app)
        await pilot.press("t")
        await settle(app, pilot)
        changed = resources[0] if which == "source" else resources[1]
        changed.write_text('<resources><string name="external">User</string></resources>')
        current = [p.read_bytes() for p in resources]
        await pilot.press("s")
        assert screen.has_unsaved_changes and [p.read_bytes() for p in resources] == current
        assert any("changed since loading" in message for message in messages)


async def test_invalid_manual_edit_save_refuses_all_files(resources, wired):
    app, messages = wired(FakeClient())
    before = [p.read_bytes() for p in resources]
    async with app.run_test(size=(140, 45)) as pilot:
        screen = await open_table(pilot, app)
        screen.query_one("#table", DataTable).move_cursor(row=1)
        await pilot.press("enter")
        assert isinstance(app.screen, EditModal)
        app.screen.query_one("#input-values-zh", Input).value = "Lost placeholder"
        await pilot.click("#save-btn")
        await pilot.press("s")
        assert screen.has_unsaved_changes and [p.read_bytes() for p in resources] == before
        assert any("placeholder" in message for message in messages)


async def test_delete_staged_refresh_and_back_confirm(resources, wired):
    app, messages = wired(FakeClient())
    before = resources[0].read_bytes()
    async with app.run_test(size=(140, 45)) as pilot:
        screen = await open_table(pilot, app)
        await pilot.press("delete")
        assert screen.deleted_keys == {"a"} and resources[0].read_bytes() == before
        await pilot.press("r")
        assert isinstance(app.screen, ConfirmModal)
        await pilot.click("#cancel")
        assert screen.has_unsaved_changes
        await pilot.press("escape")
        assert isinstance(app.screen, ConfirmModal)
        await pilot.click("#cancel")
        assert screen.has_unsaved_changes
        await pilot.press("s")
        assert "a" not in XML.parse(resources[0]) and not screen.has_unsaved_changes
        assert "string-array" in resources[1].read_text()


async def test_malformed_on_mount_disables_translate_save_and_can_recover(resources, wired):
    source, target = resources
    target.write_text("<resources><broken")
    client = FakeClient()
    app, messages = wired(client)
    async with app.run_test(size=(140, 45)) as pilot:
        screen = await open_table(pilot, app)
        assert screen.load_failed and any("Load failed" in message for message in messages)
        await pilot.press("t", "s", "ctrl+t")
        assert not client.calls and not screen.translation_running
        assert target.read_text() == "<resources><broken"
        target.write_text("<resources/>")
        await pilot.press("r")
        assert not screen.load_failed and len(screen.entries) == 3
        await pilot.press("t")
        await settle(app, pilot)
        assert client.closed and screen.has_unsaved_changes


async def test_api_failures_reported_no_full_success_and_close(resources, wired):
    client = FakeClient(handler=lambda entries, kwargs: {k: "" for k in entries})
    app, messages = wired(client)
    async with app.run_test(size=(140, 45)) as pilot:
        screen = await open_table(pilot, app)
        await pilot.press("t")
        await settle(app, pilot)
        assert not screen.has_unsaved_changes and client.closed
        assert any("failed" in message for message in messages)
        assert not (resources[0].parent.parent / "values-ja/strings.xml").exists()


async def test_partial_file_save_failure_keeps_staged_changes_and_retry(config, resources, wired, monkeypatch):
    client = FakeClient()
    app, messages = wired(client)
    real_update = XML.update_entries
    failed = False
    def update(path, entries, **kwargs):
        nonlocal failed
        if "values-ja" in str(path) and not failed:
            failed = True
            raise OSError("offline simulated disk error")
        return real_update(path, entries, **kwargs)
    async with app.run_test(size=(140, 45)) as pilot:
        screen = await open_table(pilot, app)
        await pilot.press("t")
        await settle(app, pilot)
        monkeypatch.setattr(XML, "update_entries", update)
        await pilot.press("s")
        assert screen.has_unsaved_changes
        assert XML.parse(resources[1])["a"] == "Prompt translated"
        assert not (config.project_root / "res/values-ja/strings.xml").exists()
        assert any("remaining changes retained" in message for message in messages)
        before = resources[1].read_bytes()
        await pilot.press("s")
        assert not screen.has_unsaved_changes and resources[1].read_bytes() == before
        assert XML.parse(config.project_root / "res/values-ja/strings.xml")["a"] == "Prompt translated"


async def test_changed_source_placeholders_require_matching_targets_before_save(resources, wired):
    app, messages = wired(FakeClient())
    before = [p.read_bytes() for p in resources]
    async with app.run_test(size=(140, 45)) as pilot:
        screen = await open_table(pilot, app)
        screen.on_edit_complete({"key": "b", "translations": {"values": "New %1$s"}})
        await pilot.press("s")
        assert [p.read_bytes() for p in resources] == before and screen.has_unsaved_changes
        assert any("placeholder" in message for message in messages)


async def test_repeated_cancel_does_not_interrupt_client_cleanup(resources, wired):
    started = asyncio.Event()
    closing = asyncio.Event()
    release = asyncio.Event()
    async def handler(entries, kwargs):
        started.set()
        await asyncio.Event().wait()
    class SlowCloseClient(FakeClient):
        async def close(self):
            closing.set()
            await release.wait()
            self.closed = True
    client = SlowCloseClient(handler)
    app, messages = wired(client)
    async with app.run_test(size=(140, 45)) as pilot:
        screen = await open_table(pilot, app)
        await pilot.press("t")
        await asyncio.wait_for(started.wait(), 3)
        await pilot.press("c")
        await asyncio.wait_for(closing.wait(), 3)
        await pilot.press("c", "escape")
        assert screen.translation_running and not client.closed
        release.set()
        await settle(app, pilot)
        assert client.closed and not screen.translation_running and client.active == 0


async def test_app_shutdown_cancels_requests_and_closes_client(resources, wired):
    started = asyncio.Event()
    async def handler(entries, kwargs):
        started.set()
        await asyncio.Event().wait()
    client = FakeClient(handler)
    app, messages = wired(client)
    before = resources[1].read_bytes()
    async with app.run_test(size=(140, 45)) as pilot:
        await open_table(pilot, app)
        await pilot.press("t")
        await asyncio.wait_for(started.wait(), 3)
        await pilot.press("q")
    assert client.closed and client.active == 0 and resources[1].read_bytes() == before
