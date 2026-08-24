"""Tests prompt LLM."""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from pegase_desktop.llm.config import UserProfile
from pegase_desktop.llm.personality import prompt_block
from pegase_desktop.llm.prompt import build_system_prompt
from pegase_desktop.llm.prompt_budget import BudgetLevel


class PromptTest(unittest.TestCase):
    def test_system_contains_personality_and_memories(self):
        profile = UserProfile(user_name="Test", assistant_name="Pégase")
        memories = [{"category": "user", "content": "Aime le café"}]
        notes = [{"text": "Acheter du lait", "done": False}]
        prompt = build_system_prompt(profile, memories, notes, BudgetLevel.NORMAL)
        self.assertIn("Pégase", prompt)
        self.assertIn("Test", prompt)
        self.assertIn("Aime le café", prompt)
        self.assertIn("Acheter du lait", prompt)
        self.assertIn("Personnalité Pégase", prompt)

    def test_personality_block_truncated(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "pegase-personality.md"
            path.write_text("x" * 8000, encoding="utf-8")
            from pegase_desktop.llm import personality as pg

            old = pg.PERSONALITY_FILE
            pg.PERSONALITY_FILE = path
            pg.banned_phrases.cache_clear()
            try:
                block = pg.prompt_block(1000)
                self.assertLessEqual(len(block), 1100)
            finally:
                pg.PERSONALITY_FILE = old
                pg.banned_phrases.cache_clear()


if __name__ == "__main__":
    unittest.main()
