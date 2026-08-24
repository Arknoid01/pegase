"""Sync bidirectionnelle Drive — import android-* + export diffs PC."""

from __future__ import annotations

import json
import os
import shutil
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from pegase_desktop import json_io
from pegase_desktop.conversation_store import ConversationStore
from pegase_desktop.drive_export import DriveExportError, DriveExporter, _apply_config_to_environ
from pegase_desktop.llm.config import CONTEXTS_DIR
from pegase_desktop.memory_store import MemoryStore
from pegase_desktop.notepad_store import NotepadStore
from pegase_desktop.paths import (
    CONVERSATION_META_FILE,
    PEGASE_HOME,
    conversation_shadow,
    memory_file,
    memory_shadow,
    notepad_file,
    notepad_shadow,
    recent_turns_file,
    sync_dir,
)
from pegase_desktop.sync_status import mark_synced

APPLIED_OPIDS_FILE = PEGASE_HOME / "sync" / "drive-applied-opids.json"
SYNC_INTERVAL_S = 120


@dataclass
class SyncResult:
    applied: int = 0
    exported: int = 0
    error: str = ""


def drive_mount() -> Path:
    _apply_config_to_environ()
    return Path(os.environ.get("PEGASE_DRIVE", Path.home() / "GoogleDrive"))


def is_drive_mounted() -> bool:
    mount = drive_mount()
    return mount.is_dir() and os.path.ismount(mount)


def _load_applied_ids() -> set[str]:
    raw = json_io.read_json(APPLIED_OPIDS_FILE, {"appliedOpIds": []})
    ids = raw.get("appliedOpIds") if isinstance(raw, dict) else []
    if not isinstance(ids, list):
        return set()
    return {str(x) for x in ids if x}


def _save_applied_ids(ids: set[str]) -> None:
    APPLIED_OPIDS_FILE.parent.mkdir(parents=True, exist_ok=True)
    json_io.write_json_atomic(
        APPLIED_OPIDS_FILE,
        {"appliedOpIds": sorted(ids)},
    )


def _mark_applied(ids: set[str], op_id: str) -> None:
    if op_id:
        ids.add(op_id)
        _save_applied_ids(ids)


def _ensure_bootstrap() -> None:
    for path, default in (
        (notepad_file(), {"items": []}),
        (memory_file(), []),
        (recent_turns_file(), []),
        (CONVERSATION_META_FILE, {"updatedAtMs": 0, "recordId": "recent"}),
        (APPLIED_OPIDS_FILE, {"appliedOpIds": []}),
    ):
        if not path.is_file():
            path.parent.mkdir(parents=True, exist_ok=True)
            json_io.write_json_atomic(path, default)
    for shadow, src in (
        (notepad_shadow(), notepad_file()),
        (memory_shadow(), memory_file()),
        (conversation_shadow(), recent_turns_file()),
    ):
        if not shadow.is_file() and src.is_file():
            shadow.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(src, shadow)


class DriveSyncEngine:
    """Cycle import tel→PC + export diffs PC→tel (compatible script bash)."""

    def __init__(
        self,
        exporter: DriveExporter | None = None,
        memory: MemoryStore | None = None,
        notepad: NotepadStore | None = None,
        conversation: ConversationStore | None = None,
    ) -> None:
        _apply_config_to_environ()
        self.exporter = exporter or DriveExporter()
        self.memory = memory or MemoryStore()
        self.notepad = notepad or NotepadStore()
        self.conversation = conversation or ConversationStore()

    def run_once(self) -> SyncResult:
        result = SyncResult()
        try:
            _ensure_bootstrap()
            self.exporter._ensure_drive_ready()
            result.applied = self._import_android_ops()
            result.exported = (
                self._export_notepad_diff()
                + self._export_memory_diff()
                + self._export_conversation_diff()
            )
            mark_synced("bidirectional", applied=result.applied, exported=result.exported)
        except DriveExportError as exc:
            result.error = str(exc)
            from pegase_desktop.sync_status import mark_sync_error

            mark_sync_error(str(exc))
        except OSError as exc:
            result.error = str(exc)
            from pegase_desktop.sync_status import mark_sync_error

            mark_sync_error(str(exc))
        return result

    def _import_android_ops(self) -> int:
        applied_count = 0
        applied_ids = _load_applied_ids()
        base = sync_dir()
        if not base.is_dir():
            return 0

        for device_dir in sorted(base.glob("android-*")):
            if not device_dir.is_dir():
                continue
            for op_path in sorted(device_dir.glob("op-*.json")):
                if self._apply_inbound_op(op_path, applied_ids):
                    applied_count += 1
        return applied_count

    def _apply_inbound_op(self, op_path: Path, applied_ids: set[str]) -> bool:
        try:
            op = json_io.read_json(op_path, {})
        except (OSError, json.JSONDecodeError):
            return False
        if not isinstance(op, dict):
            return False

        op_id = str(op.get("opId") or "").strip()
        if not op_id or op_id in applied_ids:
            return False

        store = str(op.get("store") or "")
        kind = str(op.get("op") or "")
        record_id = str(op.get("recordId") or "")
        ts_ms = int(op.get("tsMs") or 0)
        payload = op.get("payload")
        changed = False

        if store == self.exporter.STORE_NOTEPAD:
            if kind == "delete":
                changed = self.notepad.apply_delete(record_id, ts_ms)
            elif kind == "upsert" and isinstance(payload, dict):
                changed = self.notepad.apply_upsert(payload, ts_ms)
        elif store == self.exporter.STORE_MEMORY:
            if kind == "delete":
                changed = self.memory.apply_delete(record_id, ts_ms)
            elif kind == "upsert" and isinstance(payload, dict):
                changed = self.memory.apply_upsert(payload, ts_ms)
        elif store == self.exporter.STORE_CONVERSATION:
            if kind == "upsert" and isinstance(payload, dict):
                changed = self.conversation.apply_remote(payload, ts_ms)
        elif store == self.exporter.STORE_CONTEXT:
            if kind == "upsert" and isinstance(payload, dict):
                changed = self._apply_context(payload, ts_ms)

        _mark_applied(applied_ids, op_id)
        return changed

    def _apply_context(self, payload: dict[str, Any], ts_ms: int) -> bool:
        filename = str(payload.get("filename") or "").strip()
        content = payload.get("content")
        if not filename or not isinstance(content, str):
            return False
        path = CONTEXTS_DIR / filename
        local_ts = int(path.stat().st_mtime * 1000) if path.is_file() else 0
        remote_ts = int(payload.get("updatedAtMs") or ts_ms)
        if path.is_file() and local_ts > remote_ts:
            return False
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        return True

    def _export_notepad_diff(self) -> int:
        return self._export_items_diff(
            self.exporter.STORE_NOTEPAD,
            notepad_file(),
            notepad_shadow(),
            wrapped=True,
        )

    def _export_memory_diff(self) -> int:
        return self._export_items_diff(
            self.exporter.STORE_MEMORY,
            memory_file(),
            memory_shadow(),
            wrapped=False,
        )

    def _export_items_diff(
        self,
        store: str,
        current_path: Path,
        shadow_path: Path,
        *,
        wrapped: bool,
    ) -> int:
        exported = 0
        current_items = self._read_items(current_path, wrapped)
        shadow_items = self._read_items(shadow_path, wrapped) if shadow_path.is_file() else []
        shadow_by_id = {str(i.get("id")): i for i in shadow_items if i.get("id")}

        for item in current_items:
            rid = str(item.get("id") or "")
            if not rid:
                continue
            shadow_item = shadow_by_id.get(rid)
            if shadow_item != item:
                ts = int(item.get("updatedAtMs") or 0) or int(time.time() * 1000)
                if store == self.exporter.STORE_MEMORY:
                    self.exporter.export_memory_upsert(item)
                else:
                    self.exporter.export_notepad_upsert(item)
                exported += 1

        current_ids = {str(i.get("id")) for i in current_items if i.get("id")}
        for rid, shadow_item in shadow_by_id.items():
            if rid not in current_ids:
                ts = int(time.time() * 1000)
                self.exporter._write_op(store, "delete", rid, ts, None)
                exported += 1

        shutil.copy2(current_path, shadow_path)
        return exported

    def _export_conversation_diff(self) -> int:
        turns_path = recent_turns_file()
        shadow_path = conversation_shadow()
        if shadow_path.is_file() and turns_path.read_bytes() == shadow_path.read_bytes():
            return 0
        turns = self.conversation.load_turns_android()
        payload = self.conversation.export_payload(
            self.conversation.updated_at_ms() or int(time.time() * 1000),
            turns,
        )
        if payload.get("updatedAtMs", 0) <= 0:
            payload["updatedAtMs"] = int(time.time() * 1000)
        self.exporter.export_conversation_upsert(payload)
        return 1

    @staticmethod
    def _read_items(path: Path, wrapped: bool) -> list[dict[str, Any]]:
        raw = json_io.read_json(path, [] if not wrapped else {"items": []})
        if wrapped:
            items = raw.get("items") if isinstance(raw, dict) else []
        elif isinstance(raw, list):
            items = raw
        else:
            items = []
        return [i for i in items if isinstance(i, dict) and i.get("id")]
