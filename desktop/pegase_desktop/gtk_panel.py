"""Panneau latéral overlay — gtk4-layer-shell (Wayland) + repli animé."""

from __future__ import annotations

import logging
import os
import sys
from typing import Callable

import gi

gi.require_version("GLib", "2.0")
gi.require_version("Gtk", "4.0")
gi.require_version("Gdk", "4.0")
from gi.repository import Gdk, GLib, Gtk  # noqa: E402

from pegase_desktop.gtk_position import (
    panel_workarea,
    reset_dock_state,
    schedule_dock,
    stop_dock_watchdog,
)
from pegase_desktop.settings_store import panel_edge, panel_width

_log = logging.getLogger(__name__)
_ANIM_MS = 16
_SLIDE_STEPS = 14

_layer_shell = None


def _load_layer_shell():
    global _layer_shell
    if _layer_shell is not None:
        return _layer_shell
    try:
        gi.require_version("Gtk4LayerShell", "1.0")
        from gi.repository import Gtk4LayerShell as ls

        _layer_shell = ls
        return ls
    except (ValueError, ImportError):
        return None


def layer_shell_available() -> bool:
    return _load_layer_shell() is not None


def _gdk_display_name() -> str:
    try:
        display = Gdk.Display.get_default()
        return type(display).__name__ if display else "None"
    except Exception:
        return "None"


def _use_layer_shell_for_panel() -> bool:
    """Layer-shell ne fonctionne pas sur GNOME ; X11 utilise l'ancrage Xlib."""
    if os.environ.get("PEGASE_WAYLAND_PANEL") != "1":
        return False
    if "X11" in _gdk_display_name():
        return False
    return layer_shell_available()


def _panel_debug(msg: str) -> None:
    if os.environ.get("PEGASE_DEBUG"):
        print(f"[pegase] {msg}", file=sys.stderr, flush=True)


class SidePanelController:
    """Barre latérale droite/gauche, calque OVERLAY au-dessus des autres fenêtres."""

    def __init__(
        self,
        window: Gtk.Window,
        *,
        on_hide: Callable[[], None] | None = None,
    ) -> None:
        self.window = window
        self._on_hide = on_hide
        self._width = panel_width()
        self._edge = panel_edge()
        self._visible = False
        self._anim_source: int | None = None
        self._mapped_dock_done = False
        region = panel_workarea(window)
        self._panel_h = region[3] if region else 900
        self._layer = _load_layer_shell()
        self._use_layer = False

        _panel_debug(f"panneau edge={self._edge} display={_gdk_display_name()}")

        if self._edge == "left":
            self.window.add_css_class("side-panel-left")
        else:
            self.window.add_css_class("side-panel-right")
        self.window.add_css_class("side-panel")
        window.set_decorated(False)
        window.set_resizable(False)
        window.set_focusable(True)
        window.set_default_size(self._width, self._panel_h)
        window.set_size_request(self._width, self._panel_h)

        if _use_layer_shell_for_panel():
            self._use_layer = self._try_setup_layer_shell()

        if self._use_layer:
            _log.info("Panneau overlay Wayland (gtk4-layer-shell)")
            _panel_debug("mode panneau = layer-shell Wayland")
        else:
            _log.info("Panneau latéral (mode fenêtre — ancrage X11)")
            _panel_debug("mode panneau = ancrage X11")
            window.connect("notify::mapped", self._on_mapped_dock)

        self.hide(immediate=True)

    def _try_setup_layer_shell(self) -> bool:
        ls = self._layer
        if ls is None:
            return False
        try:
            ls.init_for_window(self.window)
            if hasattr(ls, "is_layer_window") and not ls.is_layer_window(self.window):
                return False
            ls.set_layer(self.window, ls.Layer.OVERLAY)
            ls.set_namespace(self.window, "pegase-panel")
            ls.set_keyboard_mode(self.window, ls.KeyboardMode.ON_DEMAND)
            ls.set_exclusive_zone(self.window, 0)

            top = ls.Edge.TOP
            bottom = ls.Edge.BOTTOM
            side = ls.Edge.RIGHT if self._edge == "right" else ls.Edge.LEFT
            ls.set_anchor(self.window, top, True)
            ls.set_anchor(self.window, bottom, True)
            ls.set_anchor(self.window, side, True)
            ls.set_margin(self.window, top, 0)
            ls.set_margin(self.window, bottom, 0)
            ls.set_margin(self.window, side, self._width)
            return True
        except Exception as exc:
            _log.warning("gtk4-layer-shell init échoué : %s", exc)
            return False

    def _on_mapped_dock(self, window, _pspec) -> None:
        if self._use_layer or not window.get_mapped() or not self._visible:
            return
        if self._mapped_dock_done:
            return
        self._mapped_dock_done = True
        schedule_dock(self.window, self._edge, self._width)

    def _side_edge(self):
        ls = self._layer
        return ls.Edge.RIGHT if self._edge == "right" else ls.Edge.LEFT

    def _set_side_margin(self, px: int) -> None:
        if not self._use_layer or self._layer is None:
            return
        self._layer.set_margin(self.window, self._side_edge(), max(0, px))

    def is_visible(self) -> bool:
        return self._visible

    def toggle(self) -> None:
        if self._visible:
            self.hide()
        else:
            self.show()

    def show(self) -> None:
        if self._visible:
            return
        self._visible = True
        self._mapped_dock_done = False
        self.window.set_visible(True)
        if not self._use_layer:
            schedule_dock(self.window, self._edge, self._width)
            return
        self._animate_margin(
            from_px=self._width, to_px=0, on_done=lambda: self.window.present()
        )

    def hide(self, *, immediate: bool = False) -> None:
        if not self._visible and not immediate:
            return
        self._cancel_anim()

        def done() -> None:
            self._visible = False
            stop_dock_watchdog()
            reset_dock_state()
            self.window.set_visible(False)
            if self._on_hide:
                self._on_hide()

        if immediate:
            self._set_side_margin(self._width)
            done()
            return

        if not self._use_layer:
            done()
            return

        self._animate_margin(from_px=0, to_px=self._width, on_done=done)

    def _cancel_anim(self) -> None:
        if self._anim_source is not None:
            if GLib.source_remove(self._anim_source):
                pass
            self._anim_source = None

    def _animate_margin(
        self,
        *,
        from_px: int,
        to_px: int,
        on_done: Callable[[], None],
    ) -> None:
        if not self._use_layer:
            on_done()
            return
        self._cancel_anim()
        step = [0]

        def tick() -> bool:
            t = step[0] / _SLIDE_STEPS
            ease = 1 - (1 - t) ** 3
            px = int(from_px + (to_px - from_px) * ease)
            self._set_side_margin(px)
            step[0] += 1
            if step[0] > _SLIDE_STEPS:
                self._set_side_margin(to_px)
                on_done()
                self._anim_source = None
                return False
            return True

        self._anim_source = GLib.timeout_add(_ANIM_MS, tick)
