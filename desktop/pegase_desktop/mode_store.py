"""Mode session NORMAL / WORK / DRIVE — port PegaseModeStore."""

from __future__ import annotations

from enum import Enum

from pegase_desktop import json_io
from pegase_desktop.paths import MODE_FILE


class PegaseMode(Enum):
    NORMAL = "NORMAL"
    WORK = "WORK"
    DRIVE = "DRIVE"


def get_mode() -> PegaseMode:
    raw = json_io.read_json(MODE_FILE, {"mode": "NORMAL"})
    name = raw.get("mode", "NORMAL") if isinstance(raw, dict) else "NORMAL"
    try:
        return PegaseMode[str(name).upper()]
    except KeyError:
        return PegaseMode.NORMAL


def set_mode(mode: PegaseMode) -> None:
    MODE_FILE.parent.mkdir(parents=True, exist_ok=True)
    json_io.write_json_atomic(MODE_FILE, {"mode": mode.value, "auto_drive": False})


def mode_prompt_line() -> str:
    mode = get_mode()
    if mode == PegaseMode.DRIVE:
        return (
            "Mode actuel : CONDUITE — réponses très courtes, pas de blagues, "
            "priorise l'utile et la sécurité.\n"
        )
    if mode == PegaseMode.WORK:
        return (
            "Mode actuel : TRAVAIL / concentré — réponses plus brèves, "
            "moins de digressions, priorise l'utile.\n"
        )
    return ""


def reply_max_tokens() -> int:
    mode = get_mode()
    if mode == PegaseMode.DRIVE:
        return 120
    if mode == PegaseMode.WORK:
        return 400
    return 600
