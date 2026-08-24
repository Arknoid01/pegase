"""Ancrage panneau bord écran — X11/XWayland (GNOME)."""

from __future__ import annotations

import logging
import os
import shutil
import subprocess
import sys
import threading

_log = logging.getLogger(__name__)
_docked_geometry: tuple[int, int, int, int] | None = None  # x, y, w, h


def _debug(msg: str) -> None:
    if os.environ.get("PEGASE_DEBUG"):
        print(f"[pegase] {msg}", file=sys.stderr, flush=True)


def _panel_region(window) -> tuple[int, int, int, int] | None:
    import gi

    gi.require_version("Gdk", "4.0")
    from gi.repository import Gdk

    display = Gdk.Display.get_default()
    if display is None:
        return None
    surface = window.get_surface()
    monitor = display.get_monitor_at_surface(surface) if surface else None
    if monitor is None:
        monitors = display.get_monitors()
        if monitors.get_n_items() < 1:
            return None
        monitor = monitors.get_item(0)

    if hasattr(monitor, "get_workarea"):
        wa = monitor.get_workarea()
        return wa.x, wa.y, wa.width, wa.height

    geo = monitor.get_geometry()
    return geo.x, geo.y, geo.width, geo.height


def _wmctrl_move(title: str, x: int, y: int, w: int, h: int, *, xid: int | None = None) -> bool:
    wmctrl = shutil.which("wmctrl")
    if not wmctrl:
        return False
    try:
        if xid:
            cmd = [wmctrl, "-i", "-r", str(xid), "-e", f"0,{x},{y},{w},{h}"]
        else:
            cmd = [wmctrl, "-r", title, "-e", f"0,{x},{y},{w},{h}"]
        subprocess.run(cmd, check=False, timeout=2, capture_output=True)
        return True
    except (OSError, subprocess.TimeoutExpired) as exc:
        _log.warning("wmctrl : %s", exc)
        return False


def _window_xid(window) -> int | None:
    try:
        import gi

        gi.require_version("GdkX11", "4.0")
        from gi.repository import GdkX11

        surface = window.get_surface()
        if surface is None or not isinstance(surface, GdkX11.X11Surface):
            return None
        return surface.get_xid() or None
    except (ValueError, AttributeError):
        return None


def panel_workarea(window) -> tuple[int, int, int, int] | None:
    """x, y, width, height de la zone utile pour le panneau."""
    return _panel_region(window)


def reset_dock_state() -> None:
    global _docked_geometry
    _docked_geometry = None


def dock_window_to_edge(window, edge: str, width: int, *, force: bool = False) -> bool:
    """Place la fenêtre une seule fois — sans toucher au focus clavier GTK."""
    global _docked_geometry
    import gi

    gi.require_version("Gtk", "4.0")
    gi.require_version("Gdk", "4.0")
    from gi.repository import Gdk

    region = _panel_region(window)
    if region is None:
        _debug("ancrage: géométrie introuvable")
        return False

    rx, ry, rw, rh = region
    x = rx if edge == "left" else rx + rw - width
    y = ry
    height = rh
    geom = (x, y, width, height)

    if not force and _docked_geometry == geom:
        return True

    window.set_default_size(width, height)
    window.set_size_request(width, height)
    surface = window.get_surface()
    if surface is not None and hasattr(surface, "set_gravity"):
        surface.set_gravity(Gdk.Gravity.STATIC)

    title = window.get_title() or "Pégase"
    xid = _window_xid(window)

    def _move_worker() -> None:
        global _docked_geometry
        ok = _wmctrl_move(title, x, y, width, height, xid=xid)
        if ok:
            _docked_geometry = geom
            _debug(f"ancré {edge} @ {x},{y} {width}x{height}")

    threading.Thread(target=_move_worker, daemon=True).start()
    return True


def stop_dock_watchdog() -> None:
    """Compat — plus de watchdog (causait déformation + perte focus)."""
    reset_dock_state()


def start_dock_watchdog(window, edge: str, width: int) -> None:
    """Compat — no-op."""


def schedule_dock(window, edge: str, width: int) -> None:
    """Ancrage unique après affichage — pas de repositionnement en boucle."""
    import gi

    gi.require_version("GLib", "2.0")
    from gi.repository import GLib

    def _once(_src=None) -> bool:
        if window.get_surface() is None:
            return False
        dock_window_to_edge(window, edge, width, force=True)
        return False

    GLib.idle_add(_once)
    GLib.timeout_add(200, _once)
    GLib.timeout_add(600, _once)
