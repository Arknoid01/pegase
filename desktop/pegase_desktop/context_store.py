"""Contextes .md attachés — port ContextualFileStore + AttachedContextInjector."""

from __future__ import annotations

from pathlib import Path

from pegase_desktop import json_io
from pegase_desktop.llm.config import CONTEXTS_DIR
from pegase_desktop.llm.prompt_budget import BudgetLevel, attached_max_chars

ACTIVE_FILE = CONTEXTS_DIR / "active.json"
MAX_CHARS_CLOUD = 14_000

DEFAULT_KEYWORDS: dict[str, str] = {
    "orion": "orion-context.md",
    "boucherie": "boucherie-context.md",
    "fableris": "fableris-context.md",
    "olympos": "olympos-context.md",
    "irisforge": "irisforge-context.md",
    "pegase": "pegase-context.md",
    "pégase": "pegase-context.md",
    "f1": "f1-context.md",
    "formule 1": "f1-context.md",
    "formule1": "f1-context.md",
    "grand prix": "f1-context.md",
    "f1_fan": "f1-fan-context.md",
    "fan f1": "f1-fan-context.md",
    "mémoire f1": "f1-fan-context.md",
    "memoire f1": "f1-fan-context.md",
    "personnalite": "pegase-personality.md",
    "personnalité": "pegase-personality.md",
    "personality": "pegase-personality.md",
}


class ContextStore:
    def __init__(self, contexts_dir: Path = CONTEXTS_DIR) -> None:
        self.contexts_dir = contexts_dir
        self.contexts_dir.mkdir(parents=True, exist_ok=True)
        self.keywords = dict(DEFAULT_KEYWORDS)
        self._loaded: list[str] = self._read_active()

    def _read_active(self) -> list[str]:
        raw = json_io.read_json(ACTIVE_FILE, {"loaded": []})
        loaded = raw.get("loaded") if isinstance(raw, dict) else []
        if not isinstance(loaded, list):
            return []
        return [str(x) for x in loaded if x]

    def _save_active(self) -> None:
        json_io.write_json_atomic(ACTIVE_FILE, {"loaded": self._loaded})

    def list_files(self) -> list[str]:
        return sorted(p.name for p in self.contexts_dir.glob("*.md"))

    def loaded_filenames(self) -> list[str]:
        return list(self._loaded)

    def load_keyword(self, keyword: str) -> str | None:
        key = (keyword or "").strip().lower()
        filename = self.keywords.get(key)
        if not filename:
            candidate = keyword.strip()
            if candidate.endswith(".md") and (self.contexts_dir / candidate).is_file():
                filename = candidate
            else:
                return None
        path = self._resolve(filename)
        if not path.is_file():
            return None
        if filename not in self._loaded:
            self._loaded.append(filename)
            self._save_active()
        return filename

    def unload(self, keyword: str | None = None) -> None:
        if not keyword or keyword.strip().lower() in ("all", "*", "tout"):
            self._loaded.clear()
        else:
            key = keyword.strip().lower()
            filename = self.keywords.get(key, keyword.strip())
            self._loaded = [f for f in self._loaded if f != filename]
        self._save_active()

    def build_prompt_section(self, max_chars: int = MAX_CHARS_CLOUD) -> str:
        if not self._loaded:
            return ""
        parts = ["=== Documents joints (sources de vérité) ==="]
        used = len(parts[0])
        for filename in self._loaded:
            path = self._resolve(filename)
            if not path.is_file():
                continue
            body = path.read_text(encoding="utf-8").strip()
            if not body:
                continue
            title = _title_from_filename(filename)
            header = f"\n### {title} ({filename})\n"
            chunk = header + body
            if used + len(chunk) > max_chars:
                remain = max(0, max_chars - used - len(header) - 5)
                if remain <= 0:
                    break
                chunk = header + body[:remain].rstrip() + "\n…"
            parts.append(chunk)
            used += len(chunk)
            if used >= max_chars:
                break
        if len(parts) <= 1:
            return ""
        return "\n".join(parts)

    def wrap_user_message(
        self, user_message: str, budget: BudgetLevel = BudgetLevel.NORMAL
    ) -> str:
        msg = (user_message or "").strip()
        docs = self.build_prompt_section(attached_max_chars(budget))
        if not docs:
            return msg
        return docs.strip() + "\n\n=== Message de l'utilisateur ===\n" + msg

    def prompt_pointer(self) -> str:
        if not self._loaded:
            return ""
        names = ", ".join(self._loaded[:6])
        extra = f" (+{len(self._loaded) - 6})" if len(self._loaded) > 6 else ""
        return f"Contextes chargés : {names}{extra}.\n"

    def _resolve(self, filename: str) -> Path:
        if filename == "pegase-personality.md":
            from pegase_desktop.llm.config import PERSONALITY_FILE

            if PERSONALITY_FILE.is_file():
                return PERSONALITY_FILE
        return self.contexts_dir / filename


def _title_from_filename(filename: str) -> str:
    base = filename.replace("-context.md", "").replace(".md", "")
    return base.replace("-", " ").replace("_", " ").title()
