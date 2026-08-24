"""Bloc-notes — ~/.pegase/notepad/items.json"""

from __future__ import annotations

import uuid
from datetime import date
from typing import Any

from pegase_desktop import json_io, lww
from pegase_desktop.paths import NOTEPAD_FILE


def _now_ms() -> int:
    import time

    return int(time.time() * 1000)


def _today() -> str:
    return date.today().isoformat()


class NotepadStore:
    def __init__(self, path=NOTEPAD_FILE) -> None:
        self.path = path

    def load_items(self) -> list[dict[str, Any]]:
        raw = json_io.read_json(self.path, {"items": []})
        if isinstance(raw, list):
            items = raw
        elif isinstance(raw, dict):
            items = raw.get("items") or []
        else:
            items = []
        out: list[dict[str, Any]] = []
        for item in items:
            if isinstance(item, dict) and item.get("id"):
                out.append(item)
        return out

    def save_items(self, items: list[dict[str, Any]]) -> None:
        json_io.write_json_atomic(self.path, {"items": items})

    def find_by_id(self, items: list[dict[str, Any]], record_id: str):
        for item in items:
            if item.get("id") == record_id:
                return item
        return None

    def add(self, text: str) -> dict[str, Any]:
        text = (text or "").strip()
        if not text:
            raise ValueError("texte vide")
        now = _now_ms()
        item = {
            "id": str(uuid.uuid4()),
            "text": text,
            "created": _today(),
            "dueDate": "",
            "priority": 0,
            "reminderAt": 0,
            "done": False,
            "projetTag": "",
            "updatedAtMs": now,
        }
        items = self.load_items()
        items.append(item)
        self.save_items(items)
        return item

    def apply_upsert(self, payload: dict[str, Any], ts_ms: int) -> bool:
        if not payload or ts_ms <= 0:
            return False
        record_id = (payload.get("id") or "").strip()
        text = (payload.get("text") or "").strip()
        if not record_id or not text:
            return False
        items = self.load_items()
        local = self.find_by_id(items, record_id)
        local_ts = (local or {}).get("updatedAtMs") or 0
        if local is not None and not lww.should_apply(ts_ms, local_ts):
            return False
        merged = {
            "id": record_id,
            "text": text,
            "created": payload.get("created") or _today(),
            "dueDate": payload.get("dueDate") or "",
            "priority": int(payload.get("priority") or 0),
            "reminderAt": int(payload.get("reminderAt") or 0),
            "done": bool(payload.get("done")),
            "projetTag": payload.get("projetTag") or "",
            "updatedAtMs": lww.merged_version(ts_ms, int(payload.get("updatedAtMs") or 0)),
        }
        replaced = False
        for i, item in enumerate(items):
            if item.get("id") == record_id:
                items[i] = merged
                replaced = True
                break
        if not replaced:
            items.append(merged)
        self.save_items(items)
        return True

    def apply_delete(self, record_id: str, ts_ms: int) -> bool:
        if not record_id or ts_ms <= 0:
            return False
        items = self.load_items()
        local = self.find_by_id(items, record_id)
        if local is None:
            return False
        if not lww.should_apply(ts_ms, local.get("updatedAtMs") or 0):
            return False
        items = [i for i in items if i.get("id") != record_id]
        self.save_items(items)
        return True

    def list_active(self, limit: int = 12) -> list[dict[str, Any]]:
        items = [i for i in self.load_items() if not i.get("done")]
        return items[:limit]
