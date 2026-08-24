"""Son de fin de streaming."""

from __future__ import annotations

import shutil
import subprocess
import sys

from pegase_desktop.settings_store import sound_enabled


def play_stream_done() -> None:
    if not sound_enabled():
        return
    for cmd in (
        ["paplay", "/usr/share/sounds/freedesktop/stereo/complete.oga"],
        ["aplay", "/usr/share/sounds/freedesktop/stereo/complete.oga"],
        ["canberra-gtk-play", "-i", "complete"],
    ):
        if shutil.which(cmd[0]):
            try:
                subprocess.run(cmd, check=False, timeout=2, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                return
            except (OSError, subprocess.TimeoutExpired):
                continue
    sys.stdout.write("\a")
    sys.stdout.flush()
