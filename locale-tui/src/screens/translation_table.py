"""Translation table screen."""

from __future__ import annotations

import asyncio
from typing import TYPE_CHECKING

from textual.app import ComposeResult
from textual.screen import Screen
from textual.widgets import Header, Footer, DataTable, Input, Static, ProgressBar
from textual.containers import Container, Horizontal, Vertical
from textual.binding import Binding
from textual import work

from models.entry import TranslationEntry
from services.xml_parser import StringsXmlParser
from services.translator import AITranslator
from services.dead_entry_finder import DeadEntryFinder
from services.validation import parse_fragment, validate_translation
from widgets.confirm_modal import ConfirmModal

if TYPE_CHECKING:
    from config import Config, ModuleConfig


class TranslationTableScreen(Screen):
    """Translation table screen."""

    BINDINGS = [
        Binding("escape", "go_back", "Back"),
        Binding("t", "translate_missing", "Translate"),
        Binding("ctrl+t", "retranslate_entry", "Retranslate row"),
        Binding("c", "cancel_translation", "Cancel translation"),
        Binding("d", "toggle_dead_filter", "Dead Filter"),
        Binding("m", "toggle_missing_filter", "Missing Filter"),
        Binding("slash", "focus_search", "Search"),
        Binding("delete", "delete_entry", "Delete"),
        Binding("s", "save_all", "Save"),
        Binding("enter", "edit_entry", "Edit"),
        Binding("r", "refresh", "Refresh"),
    ]

    def __init__(self, config: "Config", module: "ModuleConfig"):
        super().__init__()
        self.config = config
        self.module = module
        self.entries: list[TranslationEntry] = []
        self.filtered_entries: list[TranslationEntry] = []
        self.show_dead_only = False
        self.show_missing_only = False
        self.search_query = ""
        self.has_unsaved_changes = False
        self.translation_running = False
        self.cancel_requested = False
        self.load_failed = False
        self.baseline: dict[str, dict[str, str]] = {}
        self.fingerprints = {}
        self.deleted_keys: set[str] = set()

    def compose(self) -> ComposeResult:
        yield Header()
        yield Container(
            Vertical(
                # Status bar
                Horizontal(
                    Static(f"Module: [bold]{self.module.name}[/bold]", id="module-name"),
                    Static("", id="status"),
                    Static("", id="filter-status"),
                    id="status-bar",
                ),
                # Search box
                Input(placeholder="Search entries... (press / to focus)", id="search"),
                # Progress bar (hidden)
                ProgressBar(total=100, show_eta=False, id="progress"),
                # Translation table
                DataTable(id="table", cursor_type="row", zebra_stripes=True),
                id="content",
            ),
            id="main-container",
        )
        yield Footer()

    def on_mount(self) -> None:
        """Initialize data when screen loads."""
        # Hide progress bar
        self.query_one("#progress", ProgressBar).display = False

        # Setup table columns
        table = self.query_one("#table", DataTable)
        table.add_column("Key", key="key", width=30)

        for lang in self.config.languages:
            col_name = lang.name if len(lang.name) <= 15 else lang.code
            table.add_column(col_name, key=lang.code, width=25)

        # Malformed resources must not turn into an empty, saveable state.
        try:
            self.load_entries()
        except Exception as exc:
            self.load_failed = True
            self.notify(f"Load failed: {exc}. Translation/save disabled; fix files then refresh.", severity="error")

        # Focus table by default
        table.focus()

    def load_entries(self) -> None:
        """Load all translation entries."""
        loaded_entries = []
        all_keys: set[str] = set()
        translations_by_lang: dict[str, dict[str, str]] = {}
        excluded_by_lang: dict[str, set[str]] = {}
        fingerprints = {}

        # Collect translations from all languages
        for lang in self.config.languages:
            path = (
                self.config.project_root
                / self.module.res_path
                / lang.code
                / "strings.xml"
            )
            fingerprints[lang.code] = StringsXmlParser.fingerprint(path)
            translations = StringsXmlParser.parse(path)
            translations_by_lang[lang.code] = translations
            eligible = StringsXmlParser.parse(path, translatable_only=True)
            excluded_by_lang[lang.code] = set(translations) - set(eligible)
            all_keys.update(translations.keys())

        # Create entries
        for key in sorted(all_keys):
            entry = TranslationEntry(key=key)
            for lang_code, translations in translations_by_lang.items():
                entry.translations[lang_code] = translations.get(key)
                if key in excluded_by_lang[lang_code]:
                    entry.excluded_languages.add(lang_code)
            loaded_entries.append(entry)

        for lang in self.config.languages:
            path = self.config.project_root / self.module.res_path / lang.code / "strings.xml"
            if StringsXmlParser.fingerprint(path) != fingerprints[lang.code]:
                raise ValueError(f"File changed while loading: {path}")
        for entry in loaded_entries:
            entry.is_translatable = (entry.key in translations_by_lang.get("values", {})
                                     and "values" not in entry.excluded_languages)
        self.entries = loaded_entries
        self.baseline = translations_by_lang
        self.fingerprints = fingerprints
        self.deleted_keys.clear()
        self.has_unsaved_changes = False
        self.load_failed = False

        # Mark dead entries
        if self.module.source_patterns:
            finder = DeadEntryFinder(self.config.project_root)
            dead_count = finder.mark_dead_entries(
                self.entries, self.module.source_patterns
            )
            self.notify(f"Found {dead_count} dead entries")

        self.apply_filters()
        self.update_status()

    def apply_filters(self) -> None:
        """Apply search and filter conditions."""
        self.filtered_entries = self.entries.copy()

        # Apply dead filter
        if self.show_dead_only:
            self.filtered_entries = [e for e in self.filtered_entries if e.is_dead]

        # Apply missing filter
        if self.show_missing_only:
            lang_codes = self.config.get_language_codes()
            self.filtered_entries = [
                e for e in self.filtered_entries
                if e.has_missing_translations(lang_codes)
            ]

        # Apply search
        if self.search_query:
            query = self.search_query.lower()
            self.filtered_entries = [
                e
                for e in self.filtered_entries
                if query in e.key.lower()
                or any(query in (v or "").lower() for v in e.translations.values())
            ]

        self.refresh_table()

    def refresh_table(self) -> None:
        """Refresh table display."""
        table = self.query_one("#table", DataTable)
        table.clear()

        for entry in self.filtered_entries:
            row_data = [entry.key]
            for lang in self.config.languages:
                value = entry.translations.get(lang.code, "")
                # Highlight missing translations
                if not (value or "").strip() and lang.code != "values":
                    row_data.append("[red]MISSING[/red]")
                elif entry.is_dead:
                    row_data.append(f"[dim]{value or ''}[/dim]")
                else:
                    # Truncate long values for display
                    display_value = value or ""
                    if len(display_value) > 30:
                        display_value = display_value[:27] + "..."
                    row_data.append(display_value)

            table.add_row(*row_data, key=entry.key)

    def update_status(self) -> None:
        """Update status bar."""
        total = len(self.entries)
        missing = sum(
            1
            for e in self.entries
            if e.has_missing_translations(self.config.get_language_codes())
        )
        dead = sum(1 for e in self.entries if e.is_dead)

        status = f"Total: {total} | Missing: {missing} | Dead: {dead}"
        if self.has_unsaved_changes:
            status += " | [yellow]Unsaved[/yellow]"

        self.query_one("#status", Static).update(status)

        # Update filter status
        filter_text = []
        if self.show_dead_only:
            filter_text.append("[cyan]Dead Only[/cyan]")
        if self.show_missing_only:
            filter_text.append("[cyan]Missing Only[/cyan]")
        if self.search_query:
            filter_text.append(f"[cyan]Search: {self.search_query}[/cyan]")

        self.query_one("#filter-status", Static).update(" | ".join(filter_text))

    def on_input_changed(self, event: Input.Changed) -> None:
        """Search box content changed."""
        if event.input.id == "search":
            self.search_query = event.value
            self.apply_filters()
            self.update_status()

    def action_go_back(self) -> None:
        """Cancel a running request, otherwise confirm before discarding edits."""
        if self.translation_running:
            self.action_cancel_translation()
        elif self.has_unsaved_changes:
            self.app.push_screen(ConfirmModal("Discard unsaved changes and go back?"),
                                 callback=lambda yes: self.app.pop_screen() if yes else None)
        else:
            self.app.pop_screen()

    def _blocked(self) -> bool:
        if self.load_failed:
            self.notify("Resource load failed; fix files and refresh before editing/translating/saving.", severity="error")
            return True
        if self.translation_running:
            self.notify("Translation is running; cancel with c before editing/saving.")
            return True
        return False

    def action_focus_search(self) -> None:
        """Focus search box."""
        self.query_one("#search", Input).focus()

    def action_toggle_dead_filter(self) -> None:
        """Toggle dead entry filter."""
        self.show_dead_only = not self.show_dead_only
        self.apply_filters()
        self.update_status()
        self.notify(f"Dead filter: {'ON' if self.show_dead_only else 'OFF'}")

    def action_toggle_missing_filter(self) -> None:
        """Toggle missing translation filter."""
        self.show_missing_only = not self.show_missing_only
        self.apply_filters()
        self.update_status()
        self.notify(f"Missing filter: {'ON' if self.show_missing_only else 'OFF'}")

    def on_data_table_row_selected(self, event: DataTable.RowSelected) -> None:
        # DataTable consumes Enter itself; route its event to the existing action.
        self.action_edit_entry()

    def action_edit_entry(self) -> None:
        """Edit current selected entry."""
        from widgets.edit_modal import EditModal

        if self._blocked():
            return
        table = self.query_one("#table", DataTable)
        if not table.row_count or table.cursor_row is None:
            return

        row_key, _ = table.coordinate_to_cell_key(table.cursor_coordinate)
        entry = next((e for e in self.entries if e.key == row_key.value), None)

        if entry:
            self.app.push_screen(
                EditModal(entry, self.config.languages), callback=self.on_edit_complete
            )

    def on_edit_complete(self, result: dict | None) -> None:
        """Edit complete callback."""
        if result:
            entry_key = result["key"]
            entry = next((e for e in self.entries if e.key == entry_key), None)
            if entry:
                for lang_code, value in result["translations"].items():
                    entry.set_translation(lang_code, value)
                self.has_unsaved_changes = True
                self.refresh_table()
                self.update_status()
                self.notify(f"Updated: {entry_key}")

    def action_delete_entry(self) -> None:
        """Delete current selected entry."""
        if self._blocked():
            return
        table = self.query_one("#table", DataTable)
        if not table.row_count or table.cursor_row is None:
            return

        row_key, _ = table.coordinate_to_cell_key(table.cursor_coordinate)
        entry_key = row_key.value

        # Stage deletion; disk only changes on explicit Save.
        self.deleted_keys.add(entry_key)
        self.has_unsaved_changes = True

        # Delete from memory
        self.entries = [e for e in self.entries if e.key != entry_key]
        self.apply_filters()
        self.update_status()
        self.notify(f"Deleted: {entry_key}")

    def action_translate_missing(self) -> None:
        """Translate missing values across the module, independent of filters."""
        if not self._blocked():
            self._translate(self.entries)

    def action_retranslate_entry(self) -> None:
        """Retranslate only the selected row's existing target values, after confirmation."""
        if self._blocked():
            return
        table = self.query_one("#table", DataTable)
        if not table.row_count:
            return
        row_key, _ = table.coordinate_to_cell_key(table.cursor_coordinate)
        entry = next((e for e in self.entries if e.key == row_key.value), None)
        if entry is not None:
            self.app.push_screen(ConfirmModal(f"Retranslate existing target values for {entry.key}? Changes remain unsaved."),
                                 callback=lambda yes: self._translate([entry], retranslate=True) if yes else None)

    def action_cancel_translation(self) -> None:
        # Repeated keys must not interrupt worker/client cleanup with a second cancel.
        if self.translation_running and not self.cancel_requested:
            self.cancel_requested = True
            self.workers.cancel_group(self, "translation")

    @work(group="translation", exclusive=True)
    async def _translate(self, entries, retranslate=False) -> None:
        translator = None
        self.translation_running = True
        self.cancel_requested = False
        progress = self.query_one("#progress", ProgressBar)
        progress.display = True
        try:
            translator = AITranslator(self.config)
            def update_progress(code, current, total, message):
                progress.update(progress=100 * current / total if total else 0)
                self.query_one("#status", Static).update(message)
            count = await translator.translate_all_missing(
                entries, self.config.get_language_codes(), update_progress, retranslate=retranslate,
            )
            failures = sum(len(errors) for errors in translator.last_failures.values())
            self.notify(f"Staged {count} translations; {failures} failed. Review and press s to save.",
                        severity="warning" if failures else "information")
            for code, errors in translator.last_failures.items():
                for key, error in errors.items():
                    self.notify(f"{code}/{key}: {error}", severity="error")
        except asyncio.CancelledError:
            self.notify("Cancelled. Earlier completed languages remain staged; current language was not applied. No files saved.",
                        severity="warning")
            raise
        except Exception as exc:
            self.notify(f"Translation failed: {exc}. No automatic save.", severity="error")
        finally:
            try:
                if translator is not None:
                    try:
                        await translator.close()
                    except Exception as exc:
                        self.notify(f"Client cleanup failed: {exc}", severity="error")
            finally:
                self.translation_running = False
                progress.display = False
                self.has_unsaved_changes = bool(self.deleted_keys) or any(
                    (entry.get_translation(code) or "") != self.baseline.get(code, {}).get(entry.key, "")
                    for entry in self.entries for code in self.config.get_language_codes()
                )
                self.apply_filters()
                self.update_status()

    def action_save_all(self) -> None:
        """Save only changed nodes. Validate/preflight all files, then atomic per-file writes."""
        if self._blocked():
            return
        saved = 0
        try:
            changes = []
            for lang in self.config.languages:
                path = self.config.project_root / self.module.res_path / lang.code / "strings.xml"
                if StringsXmlParser.fingerprint(path) != self.fingerprints.get(lang.code):
                    raise ValueError(f"File changed since loading: {path}")
                delta = {}
                for entry in self.entries:
                    value = entry.get_translation(lang.code) or ""
                    changed = value != self.baseline.get(lang.code, {}).get(entry.key, "")
                    source = entry.get_translation("values") or ""
                    source_changed = source != self.baseline.get("values", {}).get(entry.key, "")
                    if value and lang.code != "values" and source and (changed or source_changed):
                        error = validate_translation(source, value)
                        if error:
                            raise ValueError(f"{lang.code}/{entry.key}: {error}")
                    if changed:
                        parse_fragment(value)  # Preflight source values too, before any file is saved.
                        delta[entry.key] = value
                changes.append((lang.code, path, delta))
            for code, path, delta in changes:
                StringsXmlParser.update_entries(path, delta, delete_keys=self.deleted_keys,
                                                expected_fingerprint=self.fingerprints.get(code))
                self.baseline[code].update(delta)
                for key in self.deleted_keys:
                    self.baseline[code].pop(key, None)
                self.fingerprints[code] = StringsXmlParser.fingerprint(path)
                saved += 1
            self.deleted_keys.clear()
            self.has_unsaved_changes = False
            self.notify("All changes saved (atomic per file).")
        except Exception as exc:
            self.has_unsaved_changes = True
            self.notify(f"Save failed: {exc}. {saved} files completed; remaining changes retained.", severity="error")
        self.update_status()

    def action_refresh(self) -> None:
        if self.translation_running:
            self.notify("Cancel translation before reloading.")
            return
        def reload(yes=True):
            if yes:
                try:
                    self.load_entries()
                    self.notify("Data refreshed!")
                except Exception as exc:
                    self.load_failed = True
                    self.notify(f"Load failed: {exc}. Translation/save disabled.", severity="error")
        if self.has_unsaved_changes:
            self.app.push_screen(ConfirmModal("Discard unsaved changes and reload?"), callback=reload)
        else:
            reload()
