"""Tests stores LWW et I/O."""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from pegase_desktop.lww import merged_version, should_apply
from pegase_desktop.memory_store import MemoryStore
from pegase_desktop.notepad_store import NotepadStore


class LwwTest(unittest.TestCase):
    def test_remote_wins_when_newer(self):
        self.assertTrue(should_apply(2000, 1000))
        self.assertFalse(should_apply(500, 1000))

    def test_merged_version(self):
        self.assertEqual(merged_version(2000, 1500), 2000)
        self.assertEqual(merged_version(1000, 1500), 1500)


class MemoryStoreTest(unittest.TestCase):
    def test_add_and_reload(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "permanent.json"
            store = MemoryStore(path)
            entry = store.add("Desktop test memory")
            self.assertTrue(entry["id"])
            reloaded = MemoryStore(path).load()
            self.assertEqual(len(reloaded), 1)
            self.assertEqual(reloaded[0]["content"], "Desktop test memory")

    def test_lww_upsert(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "permanent.json"
            store = MemoryStore(path)
            entry = store.add("local")
            rid = entry["id"]
            ok = store.apply_upsert(
                {"id": rid, "content": "remote wins", "updatedAtMs": 100},
                entry["updatedAtMs"] + 5000,
            )
            self.assertTrue(ok)
            self.assertEqual(MemoryStore(path).load()[0]["content"], "remote wins")

    def test_lww_stale_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "permanent.json"
            store = MemoryStore(path)
            entry = store.add("local wins")
            rid = entry["id"]
            ok = store.apply_upsert(
                {"id": rid, "content": "stale", "updatedAtMs": 1},
                entry["updatedAtMs"] - 1000,
            )
            self.assertFalse(ok)


class NotepadStoreTest(unittest.TestCase):
    def test_add_note(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "items.json"
            store = NotepadStore(path)
            item = store.add("Acheter lait")
            self.assertEqual(len(NotepadStore(path).load_items()), 1)
            self.assertEqual(item["text"], "Acheter lait")


if __name__ == "__main__":
    unittest.main()
