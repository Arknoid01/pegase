#!/usr/bin/env bash
# Post-install optionnel — autostart + raccourci xbindkeys (utilisateur courant).
set -euo pipefail

PEGASE_HOME="${HOME}/.pegase"
AUTOSTART="${HOME}/.config/autostart"
XBIND_DIR="${HOME}/.config/xbindkeysrc.d"
LAUNCHER="$(command -v pegase-desktop || echo pegase-desktop)"

mkdir -p "${PEGASE_HOME}/llm" "${PEGASE_HOME}/contexts" "${AUTOSTART}" "${XBIND_DIR}"

if [[ ! -f "${PEGASE_HOME}/llm/keys.env" ]]; then
  install -m 600 /usr/share/pegase-desktop/keys.env.example \
    "${PEGASE_HOME}/llm/keys.env"
fi

if [[ ! -f "${AUTOSTART}/pegase-desktop.desktop" ]]; then
  install -m 644 /usr/share/applications/pegase-desktop.desktop \
    "${AUTOSTART}/pegase-desktop.desktop"
fi

sed -e "s|@LAUNCHER@|${LAUNCHER}|g" \
  /usr/share/pegase-desktop/xbindkeys-pegase-desktop \
  > "${XBIND_DIR}/pegase-desktop"
chmod 644 "${XBIND_DIR}/pegase-desktop"

MAIN="${HOME}/.config/xbindkeysrc"
if [[ -f "${MAIN}" ]]; then
  if ! grep -q 'xbindkeysrc.d/pegase-desktop' "${MAIN}" 2>/dev/null; then
    printf '\n# Pégase Desktop\ninclude %s/pegase-desktop\n' "${XBIND_DIR}" >> "${MAIN}"
  fi
else
  printf '# xbindkeys\ninclude %s/pegase-desktop\n' "${XBIND_DIR}" > "${MAIN}"
fi

if command -v xbindkeys >/dev/null 2>&1; then
  pkill -HUP xbindkeys 2>/dev/null || xbindkeys &
fi

echo "Pégase Desktop — Super+Shift+P pour focus/lancer"
