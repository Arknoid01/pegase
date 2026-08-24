"""Souvenirs permanents — ~/.pegase/memory/permanent.json"""

from __future__ import annotations

import hashlib
import uuid
from datetime import date
from typing import Any

from pegase_desktop import json_io, lww
from pegase_desktop.paths import MEMORY_FILE


def _memory_key(category: str, content: str) -> str:
    raw = f"{(category or '').strip().lower()}\n{(content or '').strip()}"
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()[:16]


def _stable_id(category: str, content: str) -> str:
    key = _memory_key(category, content)
    return str(uuid.uuid5(uuid.NAMESPACE_URL, f"pegase-mem:{key}"))


def _ensure_sync_fields(entry: dict[str, Any]) -> None:
    if not entry.get("id"):
        entry["id"] = _stable_id(
            entry.get("category", "general"), entry.get("content", "")
        )
    if not entry.get("updatedAtMs"):
        entry["updatedAtMs"] = entry.get("lastUsedAt") or _now_ms()


def _now_ms() -> int:
    import time

    return int(time.time() * 1000)


def _today() -> str:
    return date.today().isoformat()


class MemoryStore:
    def __init__(self, path=MEMORY_FILE) -> None:
        self.path = path

    def load(self) -> list[dict[str, Any]]:
        raw = json_io.read_json(self.path, [])
        if not isinstance(raw, list):
            return []
        out: list[dict[str, Any]] = []
        for item in raw:
            if isinstance(item, dict):
                _ensure_sync_fields(item)
                out.append(item)
        return out

    def save(self, entries: list[dict[str, Any]]) -> None:
        for entry in entries:
            _ensure_sync_fields(entry)
        json_io.write_json_atomic(self.path, entries)

    def find_by_id(self, entries: list[dict[str, Any]], record_id: str):
        for entry in entries:
            if entry.get("id") == record_id:
                return entry
        return None

    def add(self, content: str, category: str = "user", importance: float = 0.85):
        content = (content or "").strip()
        if not content:
            raise ValueError("content vide")
        now = _now_ms()
        entry = {
            "id": str(uuid.uuid4()),
            "updatedAtMs": now,
            "category": category or "user",
            "content": content,
            "importance": importance,
            "createdAt": _today(),
            "source": "user",
            "lastUsedAt": now,
            "importanceAtLastUse": importance,
        }
        entries = self.load()
        entries.append(entry)
        self.save(entries)
        return entry

    def apply_upsert(self, payload: dict[str, Any], ts_ms: int) -> bool:
        if not payload or ts_ms <= 0:
            return False
        record_id = (payload.get("id") or "").strip()
        if not record_id:
            return False
        entries = self.load()
        local = self.find_by_id(entries, record_id)
        local_ts = (local or {}).get("updatedAtMs") or 0
        if local is not None and not lww.should_apply(ts_ms, local_ts):
            return False
        merged = dict(payload)
        merged["updatedAtMs"] = lww.merged_version(ts_ms, merged.get("updatedAtMs", 0))
        _ensure_sync_fields(merged)
        replaced = False
        for i, entry in enumerate(entries):
            if entry.get("id") == record_id:
                entries[i] = merged
                replaced = True
                break
        if not replaced:
            entries.append(merged)
        self.save(entries)
        return True

    def apply_delete(self, record_id: str, ts_ms: int) -> bool:
        if not record_id or ts_ms <= 0:
            return False
        entries = self.load()
        local = self.find_by_id(entries, record_id)
        if local is None:
            return False
        if not lww.should_apply(ts_ms, local.get("updatedAtMs") or 0):
            return False
        entries = [e for e in entries if e.get("id") != record_id]
        self.save(entries)
        return True

    def list_injectable(self, limit: int = 8) -> list[dict[str, Any]]:
        entries = self.load()
        valid = [
            e
            for e in entries
            if e.get("invalidAt", 0) in (0, None) and e.get("source") != "fallback"
        ]
        valid.sort(key=lambda e: e.get("importance") or 0, reverse=True)
        return valid[:limit]
