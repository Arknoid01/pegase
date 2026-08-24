"""Notifications desktop (notify-send)."""

from __future__ import annotations

import shutil
import subprocess


def notify(title: str, body: str = "", *, urgency: str = "normal") -> None:
    if not shutil.which("notify-send"):
        return
    try:
        subprocess.run(
            ["notify-send", "-u", urgency, title, body],
            check=False,
            timeout=3,
        )
    except (OSError, subprocess.TimeoutExpired):
        pass
