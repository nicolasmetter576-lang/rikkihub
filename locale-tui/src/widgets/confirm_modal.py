"""Small explicit confirmation dialog for destructive actions."""

from textual.app import ComposeResult
from textual.screen import ModalScreen
from textual.widgets import Button, Static
from textual.containers import Vertical, Horizontal


class ConfirmModal(ModalScreen[bool]):
    DEFAULT_CSS = """
    ConfirmModal { align: center middle; background: $background 60%; }
    #confirm-dialog { width: 80%; height: auto; background: $surface; border: solid $warning; padding: 1 2; }
    #confirm-actions { height: auto; align: center middle; margin-top: 1; }
    #confirm-actions Button { margin: 0 1; }
    """
    BINDINGS = [("escape", "cancel", "Cancel")]

    def __init__(self, message: str):
        super().__init__()
        self.message = message

    def compose(self) -> ComposeResult:
        with Vertical(id="confirm-dialog"):
            yield Static(self.message, markup=False)
            with Horizontal(id="confirm-actions"):
                yield Button("Confirm", id="confirm", variant="warning")
                yield Button("Cancel", id="cancel")

    def on_button_pressed(self, event: Button.Pressed) -> None:
        self.dismiss(event.button.id == "confirm")

    def action_cancel(self) -> None:
        self.dismiss(False)
