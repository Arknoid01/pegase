"""Configuration LLM — clés API, profil, modèle Groq."""

from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path

from pegase_desktop import json_io
from pegase_desktop.paths import PEGASE_HOME

LLM_DIR = PEGASE_HOME / "llm"
KEYS_FILE = Path(os.environ.get("PEGASE_LLM_KEYS", LLM_DIR / "keys.env"))
SYSTEM_KEYS_FILE = Path(os.environ.get("PEGASE_LLM_KEYS_SYSTEM", "/etc/pegase/llm.env"))
PROFILE_FILE = Path(os.environ.get("PEGASE_PROFILE_FILE", PEGASE_HOME / "profile.json"))
CONTEXTS_DIR = PEGASE_HOME / "contexts"
PERSONALITY_FILE = CONTEXTS_DIR / "pegase-personality.md"
CHAT_HISTORY_FILE = PEGASE_HOME / "chat" / "history.json"

DEFAULT_GROQ_MODEL = "openai/gpt-oss-120b"
DEFAULT_ASSISTANT_PERSONALITY = (
    "Complice et chaleureux, sarcastique avec bienveillance, bons délires. "
    "Vraiment content d'être là : présent, attentif, un peu protecteur "
    "sans étouffer. Tu te souviens des petites choses, tu célèbres les "
    "victoires, tu restes quand ça rame. Légère autodérision — "
    "pote de cœur, pas coach."
)


@dataclass(frozen=True)
class LlmKeys:
    groq: str = ""
    cerebras: str = ""
    openrouter: str = ""

    def has_any(self) -> bool:
        return bool(self.groq or self.cerebras or self.openrouter)


@dataclass(frozen=True)
class UserProfile:
    user_name: str = "Yannick"
    assistant_name: str = "Pégase"
    assistant_personality: str = DEFAULT_ASSISTANT_PERSONALITY
    groq_model: str = DEFAULT_GROQ_MODEL


def _parse_env_file(path: Path) -> dict[str, str]:
    if not path.is_file():
        return {}
    out: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        out[key.strip()] = value.strip().strip('"').strip("'")
    return out


def load_keys() -> LlmKeys:
    merged: dict[str, str] = {}
    merged.update(_parse_env_file(SYSTEM_KEYS_FILE))
    merged.update(_parse_env_file(KEYS_FILE))
    return LlmKeys(
        groq=os.environ.get("GROQ_API_KEY", merged.get("GROQ_API_KEY", "")),
        cerebras=os.environ.get("CEREBRAS_API_KEY", merged.get("CEREBRAS_API_KEY", "")),
        openrouter=os.environ.get(
            "OPENROUTER_API_KEY", merged.get("OPENROUTER_API_KEY", "")
        ),
    )


def load_profile() -> UserProfile:
    raw = json_io.read_json(PROFILE_FILE, {})
    user = raw.get("user") if isinstance(raw, dict) else {}
    prefs = user.get("preferences") if isinstance(user, dict) else {}
    if not isinstance(user, dict):
        user = {}
    if not isinstance(prefs, dict):
        prefs = {}
    llm_raw = json_io.read_json(LLM_DIR / "config.json", {})
    groq_model = DEFAULT_GROQ_MODEL
    if isinstance(llm_raw, dict):
        groq_model = llm_raw.get("groq_model") or groq_model
    groq_model = os.environ.get("PEGASE_GROQ_MODEL", groq_model)
    return UserProfile(
        user_name=user.get("name") or "Yannick",
        assistant_name=prefs.get("assistant_name") or "Pégase",
        assistant_personality=prefs.get("assistant_personality")
        or DEFAULT_ASSISTANT_PERSONALITY,
        groq_model=groq_model,
    )


def seed_profile_if_missing() -> None:
    if PROFILE_FILE.is_file():
        return
    PROFILE_FILE.parent.mkdir(parents=True, exist_ok=True)
    json_io.write_json_atomic(
        PROFILE_FILE,
        {
            "user": {
                "name": "Yannick",
                "preferences": {
                    "assistant_name": "Pégase",
                    "assistant_personality": DEFAULT_ASSISTANT_PERSONALITY,
                },
            }
        },
    )


def seed_personality_if_missing(asset_path: Path) -> None:
    if PERSONALITY_FILE.is_file():
        return
    CONTEXTS_DIR.mkdir(parents=True, exist_ok=True)
    if asset_path.is_file():
        PERSONALITY_FILE.write_text(asset_path.read_text(encoding="utf-8"), encoding="utf-8")
