#!/usr/bin/env python3
"""Enregistre Super+P dans les raccourcis GNOME (sans écraser les autres)."""

from __future__ import annotations

import subprocess
import sys

SCHEMA = "org.gnome.settings-daemon.plugins.media-keys"
CUSTOM_SCHEMA = "org.gnome.settings-daemon.plugins.media-keys.custom-keybinding"
KEY_PATH = "/org/gnome/settings-daemon/plugins/media-keys/custom-keybindings/pegase/"


def gget(key: str) -> str:
    return subprocess.check_output(["gsettings", "get", SCHEMA, key], text=True).strip()


def gset(schema: str, key: str, value: str) -> None:
    subprocess.run(["gsettings", "set", schema, key, value], check=True)


def parse_array(raw: str) -> list[str]:
    raw = raw.strip()
    if raw in ("[]", "@as []"):
        return []
    inner = raw.strip("[]")
    if not inner:
        return []
    parts: list[str] = []
    current = ""
    in_quote = False
    for ch in inner:
        if ch == "'":
            in_quote = not in_quote
            current += ch
        elif ch == "," and not in_quote:
            parts.append(current.strip())
            current = ""
        else:
            current += ch
    if current.strip():
        parts.append(current.strip())
    return [p.strip("'") for p in parts]


def install_shortcut(command: str, binding: str = "<Super>p") -> None:
    if not subprocess.run(["which", "gsettings"], capture_output=True).returncode == 0:
        print("gsettings introuvable — GNOME requis", file=sys.stderr)
        sys.exit(1)

    paths = parse_array(gget("custom-keybindings"))
    if KEY_PATH not in paths:
        paths.append(KEY_PATH)
        quoted = ", ".join(f"'{p}'" for p in paths)
        gset(SCHEMA, "custom-keybindings", f"[{quoted}]")

    base = f"{CUSTOM_SCHEMA}:{KEY_PATH}"
    gset(base, "name", "'Pégase'")
    gset(base, "command", f"'{command}'")
    gset(base, "binding", f"'{binding}'")
    print(f"Raccourci GNOME : {binding} → Pégase")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(f"Usage: {sys.argv[0]} /chemin/vers/pegase-desktop [binding]", file=sys.stderr)
        sys.exit(1)
    cmd = sys.argv[1]
    bind = sys.argv[2] if len(sys.argv) > 2 else "<Super>p"
    install_shortcut(cmd, bind)
