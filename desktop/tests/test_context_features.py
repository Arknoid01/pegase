"""Tests atlas et conversation."""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from pegase_desktop.atlas import Entity, build_atlas_block, resolve_entities
from pegase_desktop.conversation_store import ConversationStore


class ConversationStoreTest(unittest.TestCase):
    def test_lww_remote_wins(self):
        with tempfile.TemporaryDirectory() as tmp:
            turns = Path(tmp) / "turns.json"
            meta = Path(tmp) / "meta.json"
            store = ConversationStore(turns, meta)
            store.save_turns([{"fromUser": True, "text": "local"}])
            remote = {
                "id": "recent",
                "updatedAtMs": store.updated_at_ms() + 1000,
                "turns": [{"fromUser": False, "text": "remote"}],
            }
            self.assertTrue(store.apply_remote(remote, remote["updatedAtMs"]))
            loaded = store.load_turns_android()
            self.assertEqual(loaded[0]["text"], "remote")


class AtlasTest(unittest.TestCase):
    def test_resolves_project_name(self):
        entities = [
            Entity(
                id="p1",
                type="project",
                name="Pégase",
                aliases=["Orbe"],
                data={"status": "dev", "facts": ["App Android"]},
            )
        ]
        matches, _ = resolve_entities("On avance sur Pégase ?", entities)
        self.assertTrue(matches)
        self.assertGreaterEqual(matches[0].score, 0.55)
        self.assertIn("Pégase", matches[0].entity.name)


if __name__ == "__main__":
    unittest.main()
