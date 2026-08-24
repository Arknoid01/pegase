"""Charge pegase-personality.md — même source que Android."""

from __future__ import annotations

import re
from functools import lru_cache
from pathlib import Path

from pegase_desktop.llm.config import PERSONALITY_FILE

FALLBACK_BANNED = (
    "n'hésite pas",
    "il est important de noter",
    "en tant qu'assistant",
    "je suis là pour t'aider",
    "cela dépend de plusieurs facteurs",
    "excellente question",
    "super question",
)

MAX_INJECT_CHARS = 6000


def _asset_path() -> Path:
    return Path(__file__).resolve().parent.parent / "assets" / "pegase-personality.md"


def load_body() -> str:
    if PERSONALITY_FILE.is_file():
        return PERSONALITY_FILE.read_text(encoding="utf-8")
    asset = _asset_path()
    if asset.is_file():
        return asset.read_text(encoding="utf-8")
    return ""


def prompt_block(max_chars: int = MAX_INJECT_CHARS) -> str:
    body = load_body().strip()
    if not body:
        return ""
    cap = min(max_chars, MAX_INJECT_CHARS) if max_chars > 0 else MAX_INJECT_CHARS
    if len(body) > cap:
        body = body[: cap - 1].rstrip() + "\n…"
    return f"\n=== Personnalité Pégase (référence) ===\n{body}\n"


@lru_cache(maxsize=1)
def banned_phrases() -> tuple[str, ...]:
    body = load_body()
    if not body:
        return FALLBACK_BANNED
    out: list[str] = []
    in_section = False
    for line in body.splitlines():
        t = line.strip()
        if t.startswith("## ") and "liste noire" in t.lower():
            in_section = True
            continue
        if in_section and t.startswith("## "):
            break
        if not in_section or not t.startswith("- "):
            continue
        phrase = _normalize_bullet(t[2:])
        if phrase:
            out.append(phrase)
    return tuple(out) if out else FALLBACK_BANNED


def _normalize_bullet(raw: str) -> str:
    s = raw.replace("«", " ").replace("»", " ").replace("…", " ").replace("!", " ")
    paren = s.find("(")
    if paren > 0:
        s = s[:paren]
    return " ".join(s.lower().replace("'", "'").split())


def strip_banned_phrases(text: str) -> str:
    if not text or not text.strip():
        return ""
    out = text
    for banned in banned_phrases():
        out = _remove_ignore_case(out, banned)
    return re.sub(r"\s+", " ", out).strip()


def _remove_ignore_case(text: str, needle: str) -> str:
    if not needle:
        return text
    fold_hay = text.lower().replace("'", "'")
    fold_needle = needle.lower().replace("'", "'")
    result: list[str] = []
    i = 0
    while i < len(text):
        if fold_hay.startswith(fold_needle, i):
            i += len(fold_needle)
            continue
        result.append(text[i])
        i += 1
    return "".join(result)
