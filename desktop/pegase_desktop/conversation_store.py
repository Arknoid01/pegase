"""Historique chat — format Android recent_turns.json + LWW sync."""

from __future__ import annotations

import time
from typing import Any

from pegase_desktop import json_io, lww
from pegase_desktop.paths import CONVERSATION_META_FILE, RECENT_TURNS_FILE

RECORD_ID = "recent"
MAX_TURNS = 14


def _now_ms() -> int:
    return int(time.time() * 1000)


class ConversationStore:
    """Format canonique : array [{fromUser, text}, …] comme Android."""

    def __init__(
        self,
        turns_path=RECENT_TURNS_FILE,
        meta_path=CONVERSATION_META_FILE,
    ) -> None:
        self.turns_path = turns_path
        self.meta_path = meta_path

    def load_turns_android(self) -> list[dict[str, Any]]:
        raw = json_io.read_json(self.turns_path, [])
        if not isinstance(raw, list):
            return []
        out: list[dict[str, Any]] = []
        for item in raw:
            if not isinstance(item, dict):
                continue
            text = (item.get("text") or "").strip()
            if not text:
                continue
            out.append({"fromUser": bool(item.get("fromUser")), "text": text})
        return out[-MAX_TURNS:]

    def load_history_openai(self) -> list[dict[str, str]]:
        out: list[dict[str, str]] = []
        for turn in self.load_turns_android():
            role = "user" if turn["fromUser"] else "assistant"
            out.append({"role": role, "content": turn["text"]})
        return out

    def updated_at_ms(self) -> int:
        meta = json_io.read_json(self.meta_path, {})
        if isinstance(meta, dict):
            return int(meta.get("updatedAtMs") or 0)
        return 0

    def save_turns(self, turns: list[dict[str, Any]]) -> int:
        trimmed = turns[-MAX_TURNS:]
        ts = _now_ms()
        self.turns_path.parent.mkdir(parents=True, exist_ok=True)
        json_io.write_json_atomic(self.turns_path, trimmed)
        json_io.write_json_atomic(
            self.meta_path,
            {"updatedAtMs": ts, "recordId": RECORD_ID},
        )
        return ts

    def append_exchange(self, user_text: str, assistant_text: str) -> dict[str, Any]:
        turns = self.load_turns_android()
        user_text = user_text.strip()
        assistant_text = assistant_text.strip()
        if user_text:
            turns.append({"fromUser": True, "text": user_text})
        if assistant_text:
            turns.append({"fromUser": False, "text": assistant_text})
        ts = self.save_turns(turns)
        return self.export_payload(ts, turns)

    def clear(self) -> dict[str, Any]:
        ts = self.save_turns([])
        return self.export_payload(ts, [])

    def apply_remote(self, payload: dict[str, Any], ts_ms: int) -> bool:
        if not payload or ts_ms <= 0:
            return False
        local_ts = self.updated_at_ms()
        if local_ts > 0 and not lww.should_apply(ts_ms, local_ts):
            return False
        turns_raw = payload.get("turns")
        if not isinstance(turns_raw, list):
            return False
        turns: list[dict[str, Any]] = []
        for item in turns_raw:
            if not isinstance(item, dict):
                continue
            text = (item.get("text") or "").strip()
            if text:
                turns.append({"fromUser": bool(item.get("fromUser")), "text": text})
        merged_ts = lww.merged_version(ts_ms, int(payload.get("updatedAtMs") or 0))
        self.turns_path.parent.mkdir(parents=True, exist_ok=True)
        json_io.write_json_atomic(self.turns_path, turns[-MAX_TURNS:])
        json_io.write_json_atomic(
            self.meta_path,
            {"updatedAtMs": merged_ts, "recordId": RECORD_ID},
        )
        return True

    @staticmethod
    def export_payload(ts_ms: int, turns: list[dict[str, Any]]) -> dict[str, Any]:
        return {
            "id": RECORD_ID,
            "updatedAtMs": ts_ms,
            "turns": turns[-MAX_TURNS:],
        }
