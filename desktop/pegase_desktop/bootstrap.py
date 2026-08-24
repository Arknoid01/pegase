"""Bootstrap affichage — DOIT être importé avant gi/Gtk."""

from __future__ import annotations

import logging
import os
import sys


def setup_debug_logging() -> None:
    if os.environ.get("PEGASE_DEBUG"):
        logging.basicConfig(level=logging.DEBUG, format="[pegase] %(levelname)s %(message)s")


def set_prgname() -> None:
    """WM_CLASS = 'pegase-desktop' — sans ça GTK utilise 'python3', ce qui empêche
    le dock de fusionner la fenêtre lancée avec l'icône épinglée (StartupWMClass)."""
    import gi

    gi.require_version("GLib", "2.0")
    from gi.repository import GLib

    GLib.set_prgname("pegase-desktop")


def configure_panel_backend() -> None:
    """GNOME Wayland : forcer XWayland pour ancrer le panneau au bord."""
    if os.environ.get("PEGASE_WAYLAND_PANEL") == "1":
        return
    try:
        from pegase_desktop.settings_store import panel_mode

        if not panel_mode():
            return
    except Exception:
        return

    os.environ["GDK_BACKEND"] = "x11"
    os.environ.setdefault("DISPLAY", ":0")
    os.environ.pop("WAYLAND_DISPLAY", None)
    os.environ.pop("LD_PRELOAD", None)
    if os.environ.get("PEGASE_DEBUG"):
        print("[pegase] backend → X11 (panneau bord écran)", file=sys.stderr, flush=True)


def report_gdk_backend() -> None:
    if not os.environ.get("PEGASE_DEBUG"):
        return
    try:
        import gi

        gi.require_version("Gdk", "4.0")
        from gi.repository import Gdk

        display = Gdk.Display.get_default()
        name = type(display).__name__ if display else "None"
        print(f"[pegase] Gdk display = {name}", file=sys.stderr, flush=True)
    except Exception as exc:
        print(f"[pegase] Gdk display check failed: {exc}", file=sys.stderr)
