"""Tray icon — repli en barre système + orbe flottant."""

from __future__ import annotations

import logging
import os
import tkinter as tk
from typing import Callable

from pegase_desktop.orb_draw import ORB_CHROMA, draw_orb_pil, floating_orb_widget, pil_available
from pegase_desktop.settings_store import orb_position, set_orb_position, tray_enabled

_ORB_SIZE = 96
_DRAG_THRESHOLD = 6
_log = logging.getLogger(__name__)


def _x11_systray_available() -> bool:
    """True si un gestionnaire systray X11 répond (sinon backend xorg échoue)."""
    try:
        from Xlib import display as xdisplay

        d = xdisplay.Display()
        try:
            atom = d.intern_atom("_NET_SYSTEM_TRAY_S0")
            owner = d.get_selection_owner(atom)
            return owner not in (None, 0)
        finally:
            d.close()
    except Exception:
        return False


def _gtk_tray_available() -> bool:
    try:
        import gi

        gi.require_version("Gtk", "3.0")
        from gi.repository import Gtk  # noqa: F401
        return True
    except Exception:
        return False


def _appindicator_available() -> bool:
    try:
        import gi

        try:
            gi.require_version("AyatanaAppIndicator", "0.1")
            from gi.repository import AyatanaAppIndicator  # noqa: F401
            return True
        except Exception:
            gi.require_version("AppIndicator3", "0.1")
            from gi.repository import AppIndicator3  # noqa: F401
            return True
    except Exception:
        return False


def _configure_pystray_backend() -> bool:
    """Choisit un backend pystray plausible ; False → orbe flottant seulement."""
    if os.environ.get("PYSTRAY_BACKEND"):
        return True
    if os.environ.get("WAYLAND_DISPLAY"):
        if _gtk_tray_available():
            os.environ["PYSTRAY_BACKEND"] = "gtk"
            return True
        if _appindicator_available():
            os.environ["PYSTRAY_BACKEND"] = "appindicator"
            return True
        return False
    if _x11_systray_available():
        os.environ["PYSTRAY_BACKEND"] = "xorg"
        return True
    if _gtk_tray_available():
        os.environ["PYSTRAY_BACKEND"] = "gtk"
        return True
    if _appindicator_available():
        os.environ["PYSTRAY_BACKEND"] = "appindicator"
        return True
    return False


def _build_tray_image():
    if not pil_available():
        return None
    from PIL import Image, ImageDraw

    size = 64
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    draw_orb_pil(draw, 32, 32, 24)
    return img


class _FloatingOrb:
    """Orbe flottant transparent, déplaçable — clic ouvre, glisser déplace."""

    def __init__(self, root: tk.Tk, on_open: Callable[[], None]) -> None:
        self.root = root
        self.on_open = on_open
        self.win: tk.Toplevel | None = None
        self._press_root_x = 0
        self._press_root_y = 0
        self._win_start_x = 0
        self._win_start_y = 0
        self._moved = False

    def show(self) -> None:
        if self.win is not None:
            return

        x, y = orb_position()
        win = tk.Toplevel(self.root)
        win.overrideredirect(True)
        win.attributes("-topmost", True)
        try:
            win.attributes("-transparentcolor", ORB_CHROMA)
        except tk.TclError:
            pass
        try:
            win.attributes("-alpha", 1.0)
        except tk.TclError:
            pass
        win.geometry(f"{_ORB_SIZE}x{_ORB_SIZE}+{x}+{y}")
        win.configure(bg=ORB_CHROMA)

        orb = floating_orb_widget(win, size=_ORB_SIZE, chroma=ORB_CHROMA)
        orb.pack()

        for widget in (win, orb):
            widget.bind("<ButtonPress-1>", self._on_press)
            widget.bind("<B1-Motion>", self._on_drag)
            widget.bind("<ButtonRelease-1>", self._on_release)

        self.win = win

    def hide(self) -> None:
        if self.win is not None:
            self.win.destroy()
            self.win = None

    def _on_press(self, event) -> None:
        if self.win is None:
            return
        self._press_root_x = event.x_root
        self._press_root_y = event.y_root
        self._win_start_x = self.win.winfo_x()
        self._win_start_y = self.win.winfo_y()
        self._moved = False

    def _on_drag(self, event) -> None:
        if self.win is None:
            return
        dx = event.x_root - self._press_root_x
        dy = event.y_root - self._press_root_y
        if abs(dx) > _DRAG_THRESHOLD or abs(dy) > _DRAG_THRESHOLD:
            self._moved = True
        nx = max(0, self._win_start_x + dx)
        ny = max(0, self._win_start_y + dy)
        self.win.geometry(f"+{nx}+{ny}")

    def _on_release(self, _event) -> None:
        if self.win is None:
            return
        if self._moved:
            set_orb_position(self.win.winfo_x(), self.win.winfo_y())
        else:
            self.on_open()


class TrayController:
    """Minimize to tray — pystray système + orbe flottant transparent."""

    def __init__(self, root: tk.Tk, on_show: Callable[[], None], on_quit: Callable[[], None]) -> None:
        self.root = root
        self.on_show = on_show
        self.on_quit = on_quit
        self._floating = _FloatingOrb(root, on_open=self.show)
        self._icon = None
        self._started = False
        self._use_pystray = False
        self._init_pystray()

    def _init_pystray(self) -> None:
        if not tray_enabled():
            return
        if not _configure_pystray_backend():
            _log.info("Systray indisponible — orbe flottant au repli fenêtre")
            return
        try:
            import pystray

            tray_img = _build_tray_image()
            if tray_img is None:
                raise ImportError("Pillow requis pour l'icône tray")
            menu = pystray.Menu(
                pystray.MenuItem("Ouvrir Pégase", self._menu_show, default=True),
                pystray.Menu.SEPARATOR,
                pystray.MenuItem("Quitter", self._menu_quit),
            )
            self._icon = pystray.Icon("pegase-desktop", tray_img, "Pégase", menu)
            self._use_pystray = True
        except ImportError:
            self._icon = None
            self._use_pystray = False

    def _disable_pystray(self) -> None:
        self._use_pystray = False
        if self._icon is not None:
            try:
                self._icon.stop()
            except Exception:
                pass
            self._icon = None
        self._started = False

    def _on_pystray_setup(self, icon) -> None:
        """Détecte échec dock systray (xorg) → repli orbe flottant."""
        try:
            icon.visible = True
        except Exception as exc:
            _log.warning("Tray pystray indisponible : %s", exc)
            self.root.after(0, self._disable_pystray)

    def _menu_show(self, _icon=None, _item=None) -> None:
        self.root.after(0, self.show)

    def _menu_quit(self, _icon=None, _item=None) -> None:
        self.root.after(0, self.quit_app)

    def start(self) -> None:
        if not self._use_pystray or self._icon is None or self._started:
            return
        self._started = True
        setup = lambda icon: self._on_pystray_setup(icon)
        try:
            if hasattr(self._icon, "run_detached"):
                self._icon.run_detached(setup)
            else:
                import threading

                threading.Thread(target=lambda: self._icon.run(setup), daemon=True).start()
        except Exception as exc:
            _log.warning("Tray pystray start failed : %s", exc)
            self._disable_pystray()

    def hide_to_tray(self) -> None:
        self.root.withdraw()
        # Orbe toujours visible au repli — fallback si systray absent ou en échec.
        self._floating.show()

    def show(self) -> None:
        self._floating.hide()
        self.root.deiconify()
        self.root.lift()
        try:
            self.root.focus_force()
        except tk.TclError:
            pass
        self.on_show()

    def quit_app(self) -> None:
        self._floating.hide()
        if self._icon is not None:
            try:
                self._icon.stop()
            except Exception:
                pass
            self._icon = None
        self.on_quit()

    def bind_close(self) -> None:
        self.root.protocol("WM_DELETE_WINDOW", self.hide_to_tray)
