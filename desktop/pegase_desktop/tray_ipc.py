"""IPC tray — sans GTK (évite conflit GTK3/GTK4 dans le processus principal)."""

from __future__ import annotations

import logging
import socket
import subprocess
import sys
from pathlib import Path

from pegase_desktop.paths import PEGASE_HOME

_log = logging.getLogger(__name__)
TRAY_DAEMON_SOCK = PEGASE_HOME / "tray-daemon.sock"
ICON_NAME = "pegase-desktop"


def _tray_ping() -> bool:
    if not TRAY_DAEMON_SOCK.exists():
        return False
    try:
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as client:
            client.settimeout(0.3)
            client.connect(str(TRAY_DAEMON_SOCK))
            client.sendall(b"ping")
            return client.recv(8) == b"pong"
    except OSError:
        try:
            TRAY_DAEMON_SOCK.unlink(missing_ok=True)
        except OSError:
            pass
        return False


TRAY_DAEMON_LOG = PEGASE_HOME / "tray-daemon.log"


def ensure_tray_daemon(launcher: str | None = None) -> None:
    """Démarre le daemon tray GTK3 dans un processus séparé."""
    if _tray_ping():
        return
    venv_bin = Path(sys.executable).resolve().parent
    tray_bin = venv_bin / "pegase-tray"
    if launcher:
        exe = launcher
    elif tray_bin.is_file():
        exe = str(tray_bin)
    else:
        exe = sys.executable
    module = "pegase_desktop.tray_daemon"
    try:
        TRAY_DAEMON_LOG.parent.mkdir(parents=True, exist_ok=True)
        log_fd = open(TRAY_DAEMON_LOG, "a", buffering=1, encoding="utf-8")
        log_fd.write(f"\n--- lancement {__import__('datetime').datetime.now().isoformat()} ---\n")
        log_fd.flush()
        cmd = [exe] if str(exe).endswith("pegase-tray") else [exe, "-m", module]
        subprocess.Popen(
            cmd,
            start_new_session=True,
            stdin=subprocess.DEVNULL,
            stdout=log_fd,
            stderr=log_fd,
            close_fds=False,
        )
    except OSError as exc:
        _log.warning("Tray daemon non démarré : %s", exc)


def stop_tray_daemon() -> None:
    if not TRAY_DAEMON_SOCK.exists():
        return
    try:
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as client:
            client.settimeout(0.3)
            client.connect(str(TRAY_DAEMON_SOCK))
            client.sendall(b"exit-tray")
    except OSError:
        pass
