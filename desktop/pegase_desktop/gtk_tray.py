"""Tray GTK4 — repli panneau + daemon AppIndicator."""

from __future__ import annotations

import logging
from typing import Callable, TYPE_CHECKING

import gi

gi.require_version("Gtk", "4.0")
from gi.repository import Gtk  # noqa: E402

from pegase_desktop.settings_store import tray_enabled
from pegase_desktop.tray_ipc import ensure_tray_daemon, stop_tray_daemon

if TYPE_CHECKING:
    from pegase_desktop.gtk_panel import SidePanelController

_log = logging.getLogger(__name__)


class TrayController:
    def __init__(
        self,
        window: Gtk.Window,
        on_show: Callable[[], None],
        on_quit: Callable[[], None],
        panel: SidePanelController | None = None,
    ) -> None:
        self.window = window
        self.on_show = on_show
        self.on_quit = on_quit
        self.panel = panel
        self._tray_started = False

    def start(self) -> None:
        if not tray_enabled():
            return
        if self._tray_started:
            return
        self._tray_started = True
        ensure_tray_daemon()

    def hide_to_tray(self) -> None:
        if self.panel is not None:
            self.panel.hide()
        else:
            self.window.set_visible(False)

    def show(self) -> None:
        if self.panel is not None:
            self.panel.show()
        else:
            self.window.set_visible(True)
            self.window.present()
        self.on_show()

    def toggle(self) -> None:
        if self.panel is not None:
            self.panel.toggle()
            if self.panel.is_visible():
                self.on_show()
        else:
            if self.window.is_visible():
                self.hide_to_tray()
            else:
                self.show()

    def quit_app(self) -> None:
        stop_tray_daemon()
        self.on_quit()

    def bind_close(self) -> None:
        self.window.connect("close-request", self._on_close_request)

    def _on_close_request(self, _window) -> bool:
        self.hide_to_tray()
        return True
