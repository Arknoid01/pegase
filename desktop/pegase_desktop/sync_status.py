"""Suivi sync Drive — état badge header."""

from __future__ import annotations

import time
from enum import Enum

from pegase_desktop import json_io
from pegase_desktop.drive_export import is_drive_mounted
from pegase_desktop.paths import PEGASE_HOME, pc_ops_dir

META_FILE = PEGASE_HOME / "sync" / "desktop-sync-meta.json"


class SyncState(Enum):
    OK = "ok"
    UNMOUNTED = "unmounted"
    ERROR = "error"
    NEVER = "never"


def mark_synced(label: str = "export", *, applied: int = 0, exported: int = 0) -> None:
    META_FILE.parent.mkdir(parents=True, exist_ok=True)
    json_io.write_json_atomic(
        META_FILE,
        {
            "lastSyncMs": int(time.time() * 1000),
            "label": label,
            "lastApplied": applied,
            "lastExported": exported,
            "lastError": "",
        },
    )


def mark_sync_error(message: str) -> None:
    raw = json_io.read_json(META_FILE, {})
    data = raw if isinstance(raw, dict) else {}
    data["lastSyncMs"] = int(time.time() * 1000)
    data["lastError"] = message
    META_FILE.parent.mkdir(parents=True, exist_ok=True)
    json_io.write_json_atomic(META_FILE, data)


def last_sync_ms() -> int:
    raw = json_io.read_json(META_FILE, {})
    if isinstance(raw, dict):
        return int(raw.get("lastSyncMs") or 0)
    return 0


def last_error() -> str:
    raw = json_io.read_json(META_FILE, {})
    if isinstance(raw, dict):
        return str(raw.get("lastError") or "")
    return ""


def sync_state() -> SyncState:
    if not is_drive_mounted():
        return SyncState.UNMOUNTED
    if last_error():
        return SyncState.ERROR
    if last_sync_ms() <= 0:
        return SyncState.NEVER
    return SyncState.OK


def sync_badge_text() -> str:
    state = sync_state()
    if state == SyncState.UNMOUNTED:
        return "Drive ✕"
    if state == SyncState.ERROR:
        return "sync err"
    ts = last_sync_ms()
    if ts <= 0:
        return "sync —"
    delta_s = max(0, int(time.time() - ts / 1000))
    if delta_s < 60:
        return "sync OK"
    if delta_s < 3600:
        return f"sync {delta_s // 60}m"
    return f"sync {delta_s // 3600}h"


def sync_badge_color() -> str:
    from pegase_desktop.theme import P

    state = sync_state()
    if state == SyncState.UNMOUNTED:
        return P.warn
    if state == SyncState.ERROR:
        return P.drive
    if state == SyncState.NEVER:
        return P.text_muted
    return P.success
