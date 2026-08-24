"""Tests export Drive immédiat."""

from __future__ import annotations

import os
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from pegase_desktop.drive_export import DriveExporter, DriveExportError
from pegase_desktop.memory_store import MemoryStore


class DriveExporterTest(unittest.TestCase):
    def test_export_memory_writes_op_and_bumps_seq(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            drive = tmp_path / "GoogleDrive"
            pegase = drive / "Pegase" / "pc-pegase-arch"
            pegase.mkdir(parents=True)
            memory_file = tmp_path / "memory.json"
            shadow = tmp_path / "shadow.json"
            state = tmp_path / "export.json"

            store = MemoryStore(memory_file)
            entry = store.add("export test")

            with mock.patch.dict(
                os.environ,
                {
                    "PEGASE_DRIVE": str(drive),
                    "PEGASE_SYNC_DIR": str(drive / "Pegase"),
                    "PEGASE_PC_DEVICE_ID": "pc-pegase-arch",
                    "PEGASE_PC_EXPORT_STATE": str(state),
                    "PEGASE_MEMORY_FILE": str(memory_file),
                    "PEGASE_MEMORY_SHADOW": str(shadow),
                },
                clear=False,
            ):
                with mock.patch("os.path.ismount", return_value=True):
                    exporter = DriveExporter()
                    op_path = exporter.export_memory_upsert(entry)

            self.assertTrue(op_path.is_file())
            self.assertEqual(op_path.parent, pegase)
            data = op_path.read_text(encoding="utf-8")
            self.assertIn('"store": "memory"', data)
            self.assertIn('"op": "upsert"', data)
            self.assertIn(entry["id"], data)
            next_state = __import__("json").loads(state.read_text())
            self.assertEqual(next_state["nextSeq"], 2)
            self.assertTrue(shadow.is_file())

    def test_unmounted_drive_raises(self):
        with tempfile.TemporaryDirectory() as tmp:
            drive = Path(tmp) / "GoogleDrive"
            drive.mkdir()
            with mock.patch.dict(os.environ, {"PEGASE_DRIVE": str(drive)}, clear=False):
                with mock.patch("os.path.ismount", return_value=False):
                    exporter = DriveExporter()
                    with self.assertRaises(DriveExportError):
                        exporter.export_memory_upsert(
                            {"id": "x", "updatedAtMs": 1000, "content": "c"}
                        )


if __name__ == "__main__":
    unittest.main()
