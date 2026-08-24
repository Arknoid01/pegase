"""Configuration chemins sync Drive (alignée scripts/pegase-drive-sync.defaults.env)."""

from __future__ import annotations

import os
from pathlib import Path

PEGASE_HOME = Path(os.environ.get("PEGASE_HOME", Path.home() / ".pegase"))

MEMORY_DIR = PEGASE_HOME / "memory"
MEMORY_FILE = Path(
    os.environ.get("PEGASE_MEMORY_FILE", MEMORY_DIR / "permanent.json")
)
NOTEPAD_DIR = PEGASE_HOME / "notepad"
NOTEPAD_FILE = Path(
    os.environ.get("PEGASE_NOTEPAD_FILE", NOTEPAD_DIR / "items.json")
)

SYNC_STATE_DIR = PEGASE_HOME / "sync"
MEMORY_SHADOW = Path(
    os.environ.get("PEGASE_MEMORY_SHADOW", SYNC_STATE_DIR / "pc-memory-shadow.json")
)
NOTEPAD_SHADOW = Path(
    os.environ.get("PEGASE_NOTEPAD_SHADOW", SYNC_STATE_DIR / "pc-notepad-shadow.json")
)
PC_EXPORT_STATE = Path(
    os.environ.get("PEGASE_PC_EXPORT_STATE", SYNC_STATE_DIR / "pc-drive-export.json")
)
RECENT_TURNS_FILE = Path(
    os.environ.get("PEGASE_RECENT_TURNS_FILE", MEMORY_DIR / "recent_turns.json")
)
CONVERSATION_META_FILE = Path(
    os.environ.get("PEGASE_CONVERSATION_META", SYNC_STATE_DIR / "conversation-meta.json")
)
CONVERSATION_SHADOW = Path(
    os.environ.get(
        "PEGASE_CONVERSATION_SHADOW", SYNC_STATE_DIR / "pc-conversation-shadow.json"
    )
)
ENTITIES_FILE = Path(
    os.environ.get("PEGASE_ENTITIES_FILE", MEMORY_DIR / "entities.json")
)
MODE_FILE = Path(os.environ.get("PEGASE_MODE_FILE", PEGASE_HOME / "mode.json"))
COMMANDS_FILE = Path(os.environ.get("PEGASE_COMMANDS_FILE", PEGASE_HOME / "commands.json"))
EXEC_LOG_FILE = Path(os.environ.get("PEGASE_EXEC_LOG_FILE", PEGASE_HOME / "exec_log.json"))


def memory_file() -> Path:
    return Path(os.environ.get("PEGASE_MEMORY_FILE", MEMORY_DIR / "permanent.json"))


def notepad_file() -> Path:
    return Path(os.environ.get("PEGASE_NOTEPAD_FILE", NOTEPAD_DIR / "items.json"))


def memory_shadow() -> Path:
    return Path(
        os.environ.get("PEGASE_MEMORY_SHADOW", SYNC_STATE_DIR / "pc-memory-shadow.json")
    )


def notepad_shadow() -> Path:
    return Path(
        os.environ.get("PEGASE_NOTEPAD_SHADOW", SYNC_STATE_DIR / "pc-notepad-shadow.json")
    )


def conversation_shadow() -> Path:
    return Path(
        os.environ.get(
            "PEGASE_CONVERSATION_SHADOW", SYNC_STATE_DIR / "pc-conversation-shadow.json"
        )
    )


def recent_turns_file() -> Path:
    return Path(
        os.environ.get("PEGASE_RECENT_TURNS_FILE", MEMORY_DIR / "recent_turns.json")
    )


def pc_export_state() -> Path:
    return Path(
        os.environ.get("PEGASE_PC_EXPORT_STATE", SYNC_STATE_DIR / "pc-drive-export.json")
    )

DRIVE_MOUNT = Path(os.environ.get("PEGASE_DRIVE", Path.home() / "GoogleDrive"))
DRIVE_FOLDER = os.environ.get("PEGASE_DRIVE_FOLDER", "Pegase")
PC_DEVICE_ID = os.environ.get("PEGASE_PC_DEVICE_ID", "pc-pegase-arch")

DEFAULTS_FILE = Path(
    os.environ.get(
        "PEGASE_DRIVE_SYNC_DEFAULTS", "/etc/pegase/drive-sync.defaults.env"
    )
)
CONFIG_FILE = Path(
    os.environ.get("PEGASE_DRIVE_SYNC_CONFIG", "/etc/pegase/drive-sync.env")
)


def sync_dir() -> Path:
    explicit = os.environ.get("PEGASE_SYNC_DIR")
    if explicit:
        return Path(explicit)
    return DRIVE_MOUNT / DRIVE_FOLDER


def pc_ops_dir() -> Path:
    return sync_dir() / PC_DEVICE_ID
