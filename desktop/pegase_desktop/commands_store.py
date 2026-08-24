"""Commandes shell enregistrées — ~/.pegase/commands.json"""

from __future__ import annotations

import time
from typing import Any

from pegase_desktop import json_io
from pegase_desktop.paths import COMMANDS_FILE


class CommandStore:
    def __init__(self, path=COMMANDS_FILE) -> None:
        self.path = path

    def load(self) -> list[dict[str, Any]]:
        raw = json_io.read_json(self.path, [])
        if not isinstance(raw, list):
            return []
        return [i for i in raw if isinstance(i, dict) and i.get("name")]

    def save_all(self, items: list[dict[str, Any]]) -> None:
        json_io.write_json_atomic(self.path, items)

    def add(self, name: str, command: str) -> dict[str, Any]:
        name = (name or "").strip()
        command = (command or "").strip()
        if not name or not command:
            raise ValueError("nom et commande requis")
        items = [i for i in self.load() if i.get("name") != name]
        entry = {
            "name": name,
            "command": command,
            "updatedAtMs": int(time.time() * 1000),
        }
        items.append(entry)
        self.save_all(items)
        return entry

    def remove(self, name: str) -> bool:
        items = self.load()
        remaining = [i for i in items if i.get("name") != name]
        if len(remaining) == len(items):
            return False
        self.save_all(remaining)
        return True

    def get(self, name: str) -> dict[str, Any] | None:
        for item in self.load():
            if item.get("name") == name:
                return item
        return None

    def list_sorted(self) -> list[dict[str, Any]]:
        return sorted(self.load(), key=lambda i: i.get("name", "").lower())
