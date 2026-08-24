"""Instance unique — verrou fichier + socket Unix."""

from __future__ import annotations

import fcntl
import os
import socket
import subprocess
import sys
import threading
import time
from typing import Callable

from pegase_desktop.paths import PEGASE_HOME

SOCKET_PATH = PEGASE_HOME / "pegase-desktop.sock"
LOCK_PATH = PEGASE_HOME / "pegase-desktop.lock"

_lock_fd: int | None = None


def _debug(msg: str) -> None:
    if os.environ.get("PEGASE_DEBUG"):
        print(f"[pegase] {msg}", file=sys.stderr, flush=True)


def _remove_stale_socket() -> None:
    try:
        SOCKET_PATH.unlink(missing_ok=True)
    except OSError:
        pass


def release_instance_lock() -> None:
    global _lock_fd
    if _lock_fd is None:
        return
    try:
        fcntl.flock(_lock_fd, fcntl.LOCK_UN)
    except OSError:
        pass
    try:
        os.close(_lock_fd)
    except OSError:
        pass
    _lock_fd = None


def acquire_instance_lock() -> bool:
    """Verrou exclusif non bloquant — une seule instance GTK."""
    global _lock_fd
    if _lock_fd is not None:
        return True
    LOCK_PATH.parent.mkdir(parents=True, exist_ok=True)
    fd = os.open(str(LOCK_PATH), os.O_CREAT | os.O_RDWR, 0o600)
    try:
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        os.close(fd)
        return False
    _lock_fd = fd
    return True


def _other_pegase_pids() -> list[int]:
    me = os.getpid()
    found: set[int] = set()
    for pattern in (
        "pegase-desktop",
        "pegase_desktop.__main__",
        "pegase_desktop.tray_daemon",
    ):
        try:
            out = subprocess.run(
                ["pgrep", "-f", pattern],
                capture_output=True,
                text=True,
                check=False,
            )
        except OSError:
            continue
        for line in out.stdout.splitlines():
            if line.strip().isdigit():
                pid = int(line.strip())
                if pid != me:
                    found.add(pid)
    return sorted(found)


def _kill_pids(pids: list[int], *, sig: int) -> None:
    for pid in pids:
        try:
            os.kill(pid, sig)
        except OSError:
            pass


def _kill_other_pegase_processes() -> None:
    """Termine les autres instances principales (pas le processus courant)."""
    pids = _other_pegase_pids()
    if not pids:
        return
    _debug(f"arrêt PIDs {pids}")
    _kill_pids(pids, sig=15)
    time.sleep(0.35)
    still = [pid for pid in pids if _pid_alive(pid)]
    if still:
        _debug(f"SIGKILL PIDs {still}")
        _kill_pids(still, sig=9)


def _pid_alive(pid: int) -> bool:
    try:
        os.kill(pid, 0)
        return True
    except OSError:
        return False


def force_stop_existing(*, wait_s: float = 2.0) -> None:
    """Quitte l'instance en cours (socket + processus)."""
    _debug("arrêt instance existante…")
    try_quit_existing(wait_s=0.6)
    _kill_other_pegase_processes()
    stop_tray_daemon_quiet()
    deadline = time.monotonic() + wait_s
    while time.monotonic() < deadline:
        if not _other_pegase_pids() and not SOCKET_PATH.exists():
            break
        time.sleep(0.08)
    _remove_stale_socket()
    time.sleep(0.15)
    _debug("instance précédente arrêtée")


def stop_tray_daemon_quiet() -> None:
    from pegase_desktop.tray_ipc import TRAY_DAEMON_SOCK

    if not TRAY_DAEMON_SOCK.exists():
        return
    try:
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as client:
            client.settimeout(0.3)
            client.connect(str(TRAY_DAEMON_SOCK))
            client.sendall(b"exit-tray")
    except OSError:
        pass


def send_command(command: str) -> bool:
    """Envoie une commande à l'instance principale (show, quit, …)."""
    if not SOCKET_PATH.exists():
        return False
    try:
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as client:
            client.settimeout(0.4)
            client.connect(str(SOCKET_PATH))
            client.sendall(command.encode("utf-8"))
        return True
    except OSError:
        _remove_stale_socket()
        return False


def try_focus_existing() -> bool:
    """Affiche l'instance en cours (lancement menu / pegase-desktop)."""
    if send_command("show"):
        _debug("instance déjà active — panneau affiché (pas de nouvelle fenêtre)")
        return True
    return False


def try_toggle_existing() -> bool:
    """Toggle panneau (Super+P)."""
    if send_command("toggle"):
        _debug("instance déjà active — toggle envoyé")
        return True
    return False


def try_quit_existing(*, wait_s: float = 0.5) -> bool:
    """Demande à l'instance principale de quitter."""
    if not send_command("quit"):
        _remove_stale_socket()
        return False
    deadline = time.monotonic() + wait_s
    while time.monotonic() < deadline:
        if not SOCKET_PATH.exists() and not _other_pegase_pids():
            return True
        time.sleep(0.05)
    _remove_stale_socket()
    return True


def claim_primary_instance(*, toggle: bool = False, wait_s: float = 3.0) -> bool:
    """
    Retourne True si cette processus doit démarrer l'UI.
    False si une autre instance gère déjà (show/toggle envoyé).
    """
    if toggle:
        if try_toggle_existing():
            return False
    elif try_focus_existing():
        return False

    deadline = time.monotonic() + wait_s
    while time.monotonic() < deadline:
        if acquire_instance_lock():
            _debug("verrou instance obtenu")
            return True
        if toggle:
            if try_toggle_existing():
                return False
        elif try_focus_existing():
            return False
        time.sleep(0.08)

    _debug("instance déjà en cours de démarrage — abandon")
    return False


def start_listener(
    on_show: Callable[[], None],
    on_quit: Callable[[], None] | None = None,
    on_toggle: Callable[[], None] | None = None,
) -> socket.socket:
    """Écoute show / quit depuis tray ou seconde instance."""
    SOCKET_PATH.parent.mkdir(parents=True, exist_ok=True)
    try:
        SOCKET_PATH.unlink(missing_ok=True)
    except OSError:
        pass

    server = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    try:
        server.bind(str(SOCKET_PATH))
    except OSError as exc:
        _debug(f"socket bind échoué : {exc}")
        _remove_stale_socket()
        server.bind(str(SOCKET_PATH))
    server.listen(4)

    def _loop() -> None:
        while True:
            try:
                conn, _ = server.accept()
            except OSError:
                break
            try:
                data = conn.recv(64)
                if b"toggle" in data and on_toggle is not None:
                    on_toggle()
                elif b"show" in data:
                    on_show()
                elif b"quit" in data and on_quit is not None:
                    on_quit()
            finally:
                conn.close()

    threading.Thread(target=_loop, daemon=True).start()
    return server


def cleanup() -> None:
    _remove_stale_socket()
    release_instance_lock()
