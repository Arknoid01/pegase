"""Export immédiat vers Google Drive (Pegase/pc-pegase-arch/op-*.json)."""

from __future__ import annotations

import os
import shutil
import uuid
from pathlib import Path
from typing import Any

from pegase_desktop import json_io
from pegase_desktop.paths import (
    CONFIG_FILE,
    DEFAULTS_FILE,
    PC_DEVICE_ID,
    conversation_shadow,
    memory_file,
    memory_shadow,
    notepad_file,
    notepad_shadow,
    pc_export_state,
    pc_ops_dir,
    recent_turns_file,
)


class DriveExportError(OSError):
    """Drive inaccessible ou non monté."""


def _parse_env_file(path: Path) -> dict[str, str]:
    if not path.is_file():
        return {}
    out: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            continue
        key, value = line.split("=", 1)
        out[key.strip()] = value.strip()
    return out


def _apply_config_to_environ() -> None:
    """Charge defaults + config système (comme pegase-sync-drive-notepad.sh)."""
    merged: dict[str, str] = {}
    merged.update(_parse_env_file(DEFAULTS_FILE))
    merged.update(_parse_env_file(CONFIG_FILE))
    for key, value in merged.items():
        if key.startswith("PEGASE_") and key not in os.environ:
            os.environ[key] = value


def is_drive_mounted() -> bool:
    _apply_config_to_environ()
    mount = Path(os.environ.get("PEGASE_DRIVE", Path.home() / "GoogleDrive"))
    return mount.is_dir() and os.path.ismount(mount)


class DriveExporter:
    """Écrit op-NNNNNN.json dans pc-pegase-arch/ après mutation locale."""

    STORE_MEMORY = "memory"
    STORE_NOTEPAD = "notepad"
    STORE_CONVERSATION = "conversation"
    STORE_CONTEXT = "context"

    def __init__(self) -> None:
        _apply_config_to_environ()

    @property
    def pc_dir(self) -> Path:
        return pc_ops_dir()

    @property
    def device_id(self) -> str:
        return os.environ.get("PEGASE_PC_DEVICE_ID", PC_DEVICE_ID)

    @property
    def export_state_path(self) -> Path:
        return pc_export_state()

    def _ensure_drive_ready(self) -> None:
        mount = Path(os.environ.get("PEGASE_DRIVE", Path.home() / "GoogleDrive"))
        if not mount.is_dir():
            raise DriveExportError(f"Google Drive introuvable : {mount}")
        if not os.path.ismount(mount):
            raise DriveExportError(
                f"Google Drive non monté : {mount} "
                "(systemctl --user start pegase-gdrive-mount)"
            )
        self.pc_dir.mkdir(parents=True, exist_ok=True)

    def _next_seq(self) -> int:
        state = json_io.read_json(self.export_state_path, {"nextSeq": 1})
        seq = int(state.get("nextSeq") or 1)
        return max(1, seq)

    def _bump_seq(self, seq: int) -> None:
        json_io.write_json_atomic(self.export_state_path, {"nextSeq": seq + 1})

    def _write_op(
        self,
        store: str,
        op_kind: str,
        record_id: str,
        ts_ms: int,
        payload: dict[str, Any] | None,
    ) -> Path:
        self._ensure_drive_ready()
        seq = self._next_seq()
        op_id = str(uuid.uuid4())
        op_path = self.pc_dir / f"op-{seq:06d}.json"
        body: dict[str, Any] = {
            "opId": op_id,
            "seq": seq,
            "tsMs": ts_ms,
            "deviceId": self.device_id,
            "store": store,
            "op": op_kind,
            "recordId": record_id,
            "payload": payload,
        }
        json_io.write_json_atomic(op_path, body)
        self._bump_seq(seq)
        return op_path

    def _refresh_shadow(self, store: str) -> None:
        if store == self.STORE_MEMORY:
            shutil.copy2(memory_file(), memory_shadow())
        elif store == self.STORE_NOTEPAD:
            shutil.copy2(notepad_file(), notepad_shadow())
        elif store == self.STORE_CONVERSATION:
            shutil.copy2(recent_turns_file(), conversation_shadow())

    def export_memory_upsert(self, entry: dict[str, Any]) -> Path:
        record_id = entry["id"]
        ts_ms = int(entry.get("updatedAtMs") or 0)
        if ts_ms <= 0:
            raise ValueError("updatedAtMs manquant")
        path = self._write_op(
            self.STORE_MEMORY, "upsert", record_id, ts_ms, dict(entry)
        )
        self._refresh_shadow(self.STORE_MEMORY)
        return path

    def export_notepad_upsert(self, item: dict[str, Any]) -> Path:
        record_id = item["id"]
        ts_ms = int(item.get("updatedAtMs") or 0)
        if ts_ms <= 0:
            raise ValueError("updatedAtMs manquant")
        path = self._write_op(
            self.STORE_NOTEPAD, "upsert", record_id, ts_ms, dict(item)
        )
        self._refresh_shadow(self.STORE_NOTEPAD)
        return path

    def export_conversation_upsert(self, payload: dict[str, Any]) -> Path:
        record_id = str(payload.get("id") or "recent")
        ts_ms = int(payload.get("updatedAtMs") or 0)
        if ts_ms <= 0:
            raise ValueError("updatedAtMs manquant")
        path = self._write_op(
            self.STORE_CONVERSATION, "upsert", record_id, ts_ms, dict(payload)
        )
        self._refresh_shadow(self.STORE_CONVERSATION)
        return path

    def export_context_upsert(self, filename: str, content: str, ts_ms: int | None = None) -> Path:
        import time

        ts = ts_ms or int(time.time() * 1000)
        payload = {
            "filename": filename,
            "content": content,
            "updatedAtMs": ts,
        }
        path = self._write_op(self.STORE_CONTEXT, "upsert", filename, ts, payload)
        return path
