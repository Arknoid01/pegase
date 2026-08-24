"""Lance une commande dans un terminal visible qui reste ouvert après coup."""

from __future__ import annotations

import os
import shlex
import shutil
import subprocess

_TERMINALS = (
    "kgx",
    "gnome-terminal",
    "kitty",
    "konsole",
    "xfce4-terminal",
    "alacritty",
    "foot",
    "xterm",
)


def _shell() -> str:
    return os.environ.get("SHELL") or "/bin/bash"


def _keep_open_command(command: str) -> str:
    """Enchaîne la commande puis bascule sur un shell interactif — le terminal ne se ferme pas."""
    shell = _shell()
    return f"{command}; echo; echo '[terminé — Entrée pour continuer]'; exec {shlex.quote(shell)}"


def find_terminal() -> str | None:
    for name in _TERMINALS:
        if shutil.which(name):
            return name
    return None


def launch_in_terminal(command: str) -> bool:
    """Ouvre un terminal, exécute `command`, garde le terminal ouvert ensuite. True si lancé."""
    term = find_terminal()
    if term is None:
        return False
    shell = _shell()

    if term == "kitty":
        # --hold : le terminal reste ouvert après la fin du process (message natif kitty)
        argv = ["kitty", "--hold", shell, "-c", command]
    elif term == "gnome-terminal":
        argv = ["gnome-terminal", "--", shell, "-c", _keep_open_command(command)]
    elif term == "kgx":
        argv = ["kgx", "-e", f"{shell} -c {shlex.quote(_keep_open_command(command))}"]
    else:
        argv = [term, "-e", shell, "-c", _keep_open_command(command)]

    try:
        subprocess.Popen(argv, start_new_session=True)
    except OSError:
        return False
    return True
