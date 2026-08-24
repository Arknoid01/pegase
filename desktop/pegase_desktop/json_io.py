"""Lecture/écriture JSON atomique."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any


def read_json(path: Path, default: Any) -> Any:
    if not path.is_file():
        return default
    try:
        text = path.read_text(encoding="utf-8").strip()
        if not text:
            return default
        return json.loads(text)
    except (json.JSONDecodeError, OSError):
        return default


def write_json_atomic(path: Path, data: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(
        json.dumps(data, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    tmp.replace(path)
