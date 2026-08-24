"""Tests sync bidirectionnelle Drive."""

from __future__ import annotations

import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from pegase_desktop.drive_sync import DriveSyncEngine
from pegase_desktop.memory_store import MemoryStore
from pegase_desktop.notepad_store import NotepadStore
from pegase_desktop.sync_status import SyncState, mark_synced, sync_state


class DriveSyncTest(unittest.TestCase):
    def _env(self, base: Path) -> dict[str, str]:
        drive = base / "GoogleDrive"
        pegase_home = base / ".pegase"
        sync = drive / "Pegase"
        return {
            "PEGASE_DRIVE": str(drive),
            "PEGASE_SYNC_DIR": str(sync),
            "PEGASE_HOME": str(pegase_home),
            "PEGASE_PC_DEVICE_ID": "pc-pegase-arch",
            "PEGASE_MEMORY_FILE": str(pegase_home / "memory" / "permanent.json"),
            "PEGASE_NOTEPAD_FILE": str(pegase_home / "notepad" / "items.json"),
            "PEGASE_RECENT_TURNS_FILE": str(pegase_home / "memory" / "recent_turns.json"),
            "PEGASE_CONVERSATION_META": str(pegase_home / "sync" / "conversation-meta.json"),
            "PEGASE_NOTEPAD_SHADOW": str(pegase_home / "sync" / "pc-notepad-shadow.json"),
            "PEGASE_MEMORY_SHADOW": str(pegase_home / "sync" / "pc-memory-shadow.json"),
            "PEGASE_CONVERSATION_SHADOW": str(pegase_home / "sync" / "pc-conversation-shadow.json"),
            "PEGASE_PC_EXPORT_STATE": str(pegase_home / "sync" / "pc-drive-export.json"),
            "PEGASE_DRIVE_APPLIED": str(pegase_home / "sync" / "drive-applied-opids.json"),
        }

    def test_import_android_memory_op(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            env = self._env(base)
            drive = Path(env["PEGASE_DRIVE"])
            android = drive / "Pegase" / "android-pixel"
            android.mkdir(parents=True)
            pegase = drive / "Pegase" / "pc-pegase-arch"
            pegase.mkdir(parents=True)

            op = {
                "opId": "op-test-1",
                "store": "memory",
                "op": "upsert",
                "recordId": "mem-1",
                "tsMs": 1_700_000_000_000,
                "payload": {
                    "id": "mem-1",
                    "content": "depuis le tel",
                    "updatedAtMs": 1_700_000_000_000,
                },
            }
            (android / "op-000001.json").write_text(
                json.dumps(op), encoding="utf-8"
            )

            memory = MemoryStore(Path(env["PEGASE_MEMORY_FILE"]))
            meta = Path(env["PEGASE_HOME"]) / "sync" / "desktop-sync-meta.json"
            applied = Path(env["PEGASE_DRIVE_APPLIED"])
            with mock.patch.dict(os.environ, env, clear=False):
                with mock.patch("os.path.ismount", return_value=True):
                    with mock.patch("pegase_desktop.sync_status.META_FILE", meta):
                        with mock.patch(
                            "pegase_desktop.drive_sync.APPLIED_OPIDS_FILE", applied
                        ):
                            engine = DriveSyncEngine(memory=memory, notepad=NotepadStore())
                            result = engine.run_once()

            self.assertEqual(result.applied, 1)
            loaded = memory.load()
            self.assertEqual(len(loaded), 1)
            self.assertEqual(loaded[0]["content"], "depuis le tel")

    def test_export_local_memory_diff(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            env = self._env(base)
            drive = Path(env["PEGASE_DRIVE"])
            (drive / "Pegase" / "pc-pegase-arch").mkdir(parents=True)

            memory = MemoryStore(Path(env["PEGASE_MEMORY_FILE"]))
            shadow_path = Path(env["PEGASE_MEMORY_SHADOW"])
            shadow_path.parent.mkdir(parents=True, exist_ok=True)
            shadow_path.write_text("[]", encoding="utf-8")
            memory.add("local only")

            meta = Path(env["PEGASE_HOME"]) / "sync" / "desktop-sync-meta.json"
            applied = Path(env["PEGASE_DRIVE_APPLIED"])
            with mock.patch.dict(os.environ, env, clear=False):
                with mock.patch("os.path.ismount", return_value=True):
                    with mock.patch("pegase_desktop.sync_status.META_FILE", meta):
                        with mock.patch(
                            "pegase_desktop.drive_sync.APPLIED_OPIDS_FILE", applied
                        ):
                            engine = DriveSyncEngine(memory=memory, notepad=NotepadStore())
                            result = engine.run_once()

            ops = list((drive / "Pegase" / "pc-pegase-arch").glob("op-*.json"))
            self.assertGreaterEqual(result.exported, 1)
            self.assertTrue(ops)


class SyncStatusTest(unittest.TestCase):
    def test_unmounted_state(self):
        with mock.patch("pegase_desktop.sync_status.is_drive_mounted", return_value=False):
            self.assertEqual(sync_state(), SyncState.UNMOUNTED)

    def test_ok_after_mark(self):
        with tempfile.TemporaryDirectory() as tmp:
            meta = Path(tmp) / "sync-meta.json"
            with mock.patch("pegase_desktop.sync_status.is_drive_mounted", return_value=True):
                with mock.patch("pegase_desktop.sync_status.META_FILE", meta):
                    mark_synced("test")
                    self.assertEqual(sync_state(), SyncState.OK)


if __name__ == "__main__":
    unittest.main()
