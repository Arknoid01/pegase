#!/usr/bin/env bash
# Toggle special workspace Pégase (Hyprland). Lance l'app si besoin.
set -euo pipefail

PEGASE_BIN="${PEGASE_BIN:-${HOME}/.local/bin/pegase-desktop}"
SPECIAL_WS="${PEGASE_SPECIAL_WS:-pegase}"

if ! command -v hyprctl >/dev/null 2>&1; then
  echo "hyprctl introuvable — session Hyprland requise" >&2
  exit 1
fi

if [[ -x "${PEGASE_BIN}" ]]; then
  "${PEGASE_BIN}" &
elif command -v pegase-desktop >/dev/null 2>&1; then
  pegase-desktop &
else
  echo "pegase-desktop introuvable — lance scripts/install-pegase-desktop.sh" >&2
  exit 1
fi

sleep 0.12
hyprctl dispatch togglespecialworkspace "${SPECIAL_WS}"
