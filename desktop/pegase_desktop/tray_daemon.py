"""Daemon tray GTK3 + Ayatana AppIndicator (processus séparé — évite conflit GTK4)."""

from __future__ import annotations

import logging
import socket
import subprocess
import threading
from pathlib import Path

from pegase_desktop.tray_ipc import ICON_NAME, TRAY_DAEMON_SOCK

_log = logging.getLogger(__name__)


def _send_command(command: str) -> bool:
    from pegase_desktop.single_instance import send_command

    return send_command(command)


def _socket_path() -> Path:
    from pegase_desktop.single_instance import SOCKET_PATH

    return SOCKET_PATH


def _appindicator_available() -> bool:
    import gi

    gi.require_version("Gtk", "3.0")
    try:
        gi.require_version("AyatanaAppIndicator", "0.1")
        from gi.repository import AyatanaAppIndicator  # noqa: F401
        return True
    except (ValueError, ImportError):
        try:
            gi.require_version("AppIndicator3", "0.1")
            from gi.repository import AppIndicator3  # noqa: F401
            return True
        except (ValueError, ImportError):
            return False


def _run_daemon() -> int:
    if not _appindicator_available():
        _log.error("libayatana-appindicator / python-gobject GTK3 requis")
        return 1

    import gi

    gi.require_version("Gtk", "3.0")
    try:
        gi.require_version("AyatanaAppIndicator", "0.1")
        from gi.repository import AyatanaAppIndicator as AI
        indicator_new = AI.Indicator.new
        category = AI.IndicatorCategory.APPLICATION_STATUS
        status_active = AI.IndicatorStatus.ACTIVE
    except (ValueError, ImportError):
        gi.require_version("AppIndicator3", "0.1")
        from gi.repository import AppIndicator3 as AI

        indicator_new = AI.Indicator.new
        category = AI.IndicatorCategory.APPLICATION_STATUS
        status_active = AI.IndicatorStatus.ACTIVE

    from gi.repository import GLib, Gtk

    TRAY_DAEMON_SOCK.parent.mkdir(parents=True, exist_ok=True)
    try:
        TRAY_DAEMON_SOCK.unlink(missing_ok=True)
    except OSError:
        pass

    server = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    server.bind(str(TRAY_DAEMON_SOCK))
    server.listen(4)
    stop = threading.Event()

    def _serve() -> None:
        while not stop.is_set():
            try:
                server.settimeout(0.5)
                conn, _ = server.accept()
            except socket.timeout:
                continue
            except OSError:
                break
            try:
                data = conn.recv(64)
                if b"ping" in data:
                    conn.sendall(b"pong")
                elif b"exit-tray" in data or b"exit" in data:
                    conn.sendall(b"ok")
                    stop.set()
                    GLib.idle_add(Gtk.main_quit)
            finally:
                conn.close()
        try:
            server.close()
        except OSError:
            pass

    threading.Thread(target=_serve, daemon=True).start()

    def _open(_widget=None) -> None:
        if not _send_command("show"):
            launcher = Path.home() / ".local/bin/pegase-desktop"
            if launcher.is_file():
                subprocess.Popen([str(launcher)], start_new_session=True)
            else:
                subprocess.Popen(["pegase-desktop"], start_new_session=True)

    def _quit(_widget=None) -> None:
        sock = _socket_path()
        if sock.exists():
            _send_command("quit")
        stop.set()
        Gtk.main_quit()

    indicator = indicator_new("pegase-desktop", ICON_NAME, category)
    indicator.set_title("Pégase")
    indicator.set_status(status_active)

    menu = Gtk.Menu()
    item_open = Gtk.MenuItem(label="Ouvrir Pégase")
    item_open.connect("activate", _open)
    menu.append(item_open)
    menu.append(Gtk.SeparatorMenuItem())
    item_quit = Gtk.MenuItem(label="Quitter")
    item_quit.connect("activate", _quit)
    menu.append(item_quit)
    menu.show_all()

    indicator.set_menu(menu)
    if hasattr(indicator, "connect"):
        try:
            indicator.connect("activate", _open)
        except TypeError:
            pass

    try:
        Gtk.main()
    finally:
        stop.set()
        try:
            TRAY_DAEMON_SOCK.unlink(missing_ok=True)
        except OSError:
            pass
    return 0


def main() -> None:
    logging.basicConfig(level=logging.INFO)
    raise SystemExit(_run_daemon())


if __name__ == "__main__":
    main()
