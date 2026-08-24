#!/usr/bin/env bash
# Installe Pégase Desktop (venv + .desktop + lanceur).
# Usage : ./scripts/install-pegase-desktop.sh

set -euo pipefail

BUILD_USER="${PEGASE_BUILD_USER:-$(id -un)}"
DESKTOP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
VENV="${DESKTOP_DIR}/.venv"
APPS_DIR="${HOME}/.local/share/applications"
ICONS_DIR="${HOME}/.local/share/icons/hicolor/scalable/apps"
BIN_DIR="${HOME}/.local/bin"
LAUNCHER="${BIN_DIR}/pegase-desktop"

log() { printf '[pegase-desktop-install] %s\n' "$*"; }

if [[ "$(id -un)" != "${BUILD_USER}" ]]; then
  echo "Utilisateur attendu : ${BUILD_USER}" >&2
  exit 1
fi

if ! command -v python3 >/dev/null 2>&1; then
  echo "python3 requis" >&2
  exit 1
fi

for pkg in gtk4 libadwaita python-gobject libappindicator-gtk3 xorg-xwayland wmctrl; do
  if ! pacman -Q "${pkg}" >/dev/null 2>&1; then
    log "Paquet système recommandé : sudo pacman -S ${pkg}"
  fi
done

if [[ ! -d "${VENV}" ]]; then
  log "Création venv ${VENV}"
  python3 -m venv "${VENV}"
fi

"${VENV}/bin/pip" install -q -e "${DESKTOP_DIR}[tray]"

mkdir -p "${HOME}/.pegase/llm" "${HOME}/.pegase/contexts" "${HOME}/.pegase/chat"
if [[ ! -f "${HOME}/.pegase/llm/keys.env" ]]; then
  install -m 600 "${DESKTOP_DIR}/share/keys.env.example" "${HOME}/.pegase/llm/keys.env"
  log "Template clés : ~/.pegase/llm/keys.env (ajoute GROQ_API_KEY=…)"
fi
if [[ ! -f "${HOME}/.pegase/contexts/pegase-personality.md" ]]; then
  install -m 644 "${DESKTOP_DIR}/pegase_desktop/assets/pegase-personality.md" \
    "${HOME}/.pegase/contexts/pegase-personality.md"
fi

mkdir -p "${APPS_DIR}" "${ICONS_DIR}" "${BIN_DIR}"
install -m 644 "${DESKTOP_DIR}/share/pegase-desktop.svg" "${ICONS_DIR}/pegase-desktop.svg"

cat > "${LAUNCHER}" <<EOF
#!/usr/bin/env bash
if [[ "\${PEGASE_WAYLAND_PANEL:-}" != "1" && -n "\${WAYLAND_DISPLAY:-}" ]]; then
  export GDK_BACKEND=x11
  export DISPLAY="\${DISPLAY:-:0}"
  unset WAYLAND_DISPLAY
  unset LD_PRELOAD
fi
exec "${VENV}/bin/pegase-desktop" "\$@"
EOF
chmod 755 "${LAUNCHER}"

TRAY_LAUNCHER="${BIN_DIR}/pegase-tray"
cat > "${TRAY_LAUNCHER}" <<EOF
#!/usr/bin/env bash
exec "${VENV}/bin/python" -m pegase_desktop.tray_daemon "\$@"
EOF
chmod 755 "${TRAY_LAUNCHER}"

TOGGLE_LAUNCHER="${BIN_DIR}/pegase-toggle"
cat > "${TOGGLE_LAUNCHER}" <<EOF
#!/usr/bin/env bash
if [[ "\${PEGASE_WAYLAND_PANEL:-}" != "1" && -n "\${WAYLAND_DISPLAY:-}" ]]; then
  export GDK_BACKEND=x11
  export DISPLAY="\${DISPLAY:-:0}"
  unset WAYLAND_DISPLAY
  unset LD_PRELOAD
fi
exec "${VENV}/bin/pegase-desktop" --toggle "\$@"
EOF
chmod 755 "${TOGGLE_LAUNCHER}"

sed -e "s|@EXEC@|${LAUNCHER}|g" \
    -e "s|@ICON@|${ICONS_DIR}/pegase-desktop.svg|g" \
    "${DESKTOP_DIR}/share/pegase-desktop.desktop" \
    > "${APPS_DIR}/pegase-desktop.desktop"
chmod 644 "${APPS_DIR}/pegase-desktop.desktop"

if command -v update-desktop-database >/dev/null 2>&1; then
  update-desktop-database "${APPS_DIR}" 2>/dev/null || true
fi

# Raccourci GNOME natif Super+P
if command -v gsettings >/dev/null 2>&1; then
  python3 "${DESKTOP_DIR}/scripts/install-gnome-shortcut.py" "${TOGGLE_LAUNCHER}" "<Super>p" \
    && log "Raccourci GNOME : Super+P (toggle)" \
    || log "Raccourci GNOME non configuré (gsettings)"
else
  log "Optionnel : gsettings (session GNOME) pour Super+P"
fi

# xbindkeys — fallback hors GNOME / session X11
XBIND_DIR="${HOME}/.config/xbindkeysrc.d"
mkdir -p "${XBIND_DIR}"
sed -e "s|@LAUNCHER@|${LAUNCHER}|g" \
  "${DESKTOP_DIR}/share/xbindkeys-pegase-desktop" \
  > "${XBIND_DIR}/pegase-desktop"
chmod 644 "${XBIND_DIR}/pegase-desktop"

MAIN_XBIND="${HOME}/.config/xbindkeysrc"
if [[ -f "${MAIN_XBIND}" ]]; then
  if ! grep -q 'xbindkeysrc.d/pegase-desktop' "${MAIN_XBIND}" 2>/dev/null; then
    printf '\n# Pégase Desktop\ninclude %s/pegase-desktop\n' "${XBIND_DIR}" >> "${MAIN_XBIND}"
  fi
else
  printf '# xbindkeys — Pégase Desktop\ninclude %s/pegase-desktop\n' "${XBIND_DIR}" > "${MAIN_XBIND}"
fi

if command -v xbindkeys >/dev/null 2>&1; then
  pkill -HUP xbindkeys 2>/dev/null || xbindkeys &
  log "Raccourci global : Super+Shift+P"
else
  log "Optionnel : sudo pacman -S xbindkeys  (Super+Shift+P)"
fi

# Autostart optionnel (décommente la ligne suivante pour lancer au login)
# mkdir -p "${HOME}/.config/autostart"
# install -m 644 "${APPS_DIR}/pegase-desktop.desktop" "${HOME}/.config/autostart/pegase-desktop.desktop"

log "Installé : ${LAUNCHER}"
log "Dock / menu : Pégase Desktop"
log "Test : pegase-desktop   ou   ${LAUNCHER}"
