"""Préférences UI Pégase Desktop."""

from __future__ import annotations

from pathlib import Path

from pegase_desktop import json_io
from pegase_desktop.paths import PEGASE_HOME

SETTINGS_FILE = PEGASE_HOME / "desktop-settings.json"


def load_settings() -> dict:
    raw = json_io.read_json(SETTINGS_FILE, {})
    return raw if isinstance(raw, dict) else {}


def save_settings(data: dict) -> None:
    SETTINGS_FILE.parent.mkdir(parents=True, exist_ok=True)
    json_io.write_json_atomic(SETTINGS_FILE, data)


def sound_enabled() -> bool:
    return bool(load_settings().get("sound_enabled", True))


def set_sound_enabled(enabled: bool) -> None:
    data = load_settings()
    data["sound_enabled"] = enabled
    save_settings(data)


def tray_enabled() -> bool:
    return bool(load_settings().get("tray_enabled", True))


def set_tray_enabled(enabled: bool) -> None:
    data = load_settings()
    data["tray_enabled"] = enabled
    save_settings(data)


def panel_mode() -> bool:
    """True = barre latérale overlay (défaut)."""
    return bool(load_settings().get("panel_mode", True))


def panel_width() -> int:
    try:
        w = int(load_settings().get("panel_width", 420))
        return max(340, min(560, w))
    except (TypeError, ValueError):
        return 420


def panel_edge() -> str:
    edge = str(load_settings().get("panel_edge", "left")).lower()
    return edge if edge in ("left", "right") else "left"


def orb_position() -> tuple[int, int]:
    raw = load_settings().get("orb_position")
    if isinstance(raw, dict):
        try:
            return int(raw.get("x", 20)), int(raw.get("y", 20))
        except (TypeError, ValueError):
            pass
    return 20, 20


def set_orb_position(x: int, y: int) -> None:
    data = load_settings()
    data["orb_position"] = {"x": x, "y": y}
    save_settings(data)
