#!/usr/bin/env bash
# Installe pegase-sync-drive-notepad.sh + timer systemd user.
# Usage : sudo ./scripts/install-pegase-sync-drive-notepad.sh
#         ./scripts/install-pegase-sync-drive-notepad.sh   (si déjà root → relance en Pegase)

set -euo pipefail

BUILD_USER="${PEGASE_BUILD_USER:-Pegase}"
SCRIPT_NAME="pegase-sync-drive-notepad.sh"
SERVICE_NAME="pegase-sync-drive-notepad.service"
TIMER_NAME="pegase-sync-drive-notepad.timer"
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="${REPO}/scripts/${SCRIPT_NAME}"
DEFAULTS_SRC="${REPO}/scripts/pegase-drive-sync.defaults.env"
DEST="/usr/local/bin/${SCRIPT_NAME}"
CONFIG_DIR="/etc/pegase"
CONFIG="${CONFIG_DIR}/drive-sync.env"
DEFAULTS_DEST="${CONFIG_DIR}/drive-sync.defaults.env"
USER_UNIT_DIR="/home/${BUILD_USER}/.config/systemd/user"

log() { printf '[pegase-drive-sync-install] %s\n' "$*"; }

if [[ "${1:-}" == "--user-phase" ]]; then
  shift
  if [[ "$(id -un)" != "${BUILD_USER}" ]]; then
    echo "Phase user : exécuter via sudo $0" >&2
    exit 1
  fi
  systemctl --user daemon-reload
  systemctl --user enable --now "${TIMER_NAME}"
  echo ""
  echo "=== Sync Drive bloc-notes + mémoires ==="
  echo "Dossier : ~/GoogleDrive/${PEGASE_DRIVE_FOLDER:-Pegase}"
  echo "Import tel→PC : android-*/ | Export PC→tel : ${PEGASE_PC_DEVICE_ID:-pc-pegase-arch}/"
  echo "Timer : systemctl --user status ${TIMER_NAME}"
  echo "Manuel : ${DEST}"
  exit 0
fi

if [[ "${EUID:-0}" -ne 0 ]]; then
  echo "Relance avec sudo : sudo $0" >&2
  exit 1
fi

if [[ ! -d "/home/${BUILD_USER}" ]]; then
  echo "Utilisateur introuvable : ${BUILD_USER}" >&2
  exit 1
fi

loginctl enable-linger "${BUILD_USER}" 2>/dev/null || true

if [[ ! -f "${SRC}" ]]; then
  echo "Script source introuvable : ${SRC}" >&2
  exit 1
fi

install -m 755 "${SRC}" "${DEST}"
echo "Installé : ${DEST}"

if [[ -f "${DEFAULTS_SRC}" ]]; then
  install -m 644 "${DEFAULTS_SRC}" "${DEFAULTS_DEST}"
  echo "Defaults : ${DEFAULTS_DEST}"
fi

install -d -m 755 "${CONFIG_DIR}"
# shellcheck disable=SC1090
source "${DEFAULTS_DEST}" 2>/dev/null || true
DRIVE_FOLDER="${PEGASE_DRIVE_FOLDER:-Pegase}"
PC_ID="${PEGASE_PC_DEVICE_ID:-pc-pegase-arch}"
DESIRED_SYNC_DIR="/home/${BUILD_USER}/GoogleDrive/${DRIVE_FOLDER}"
if [[ ! -f "${CONFIG}" ]]; then
  cat > "${CONFIG}" <<EOF
# Pégase — sync Drive (surcharges locales ; defaults dans drive-sync.defaults.env)
PEGASE_HOME=/home/${BUILD_USER}/.pegase
PEGASE_DRIVE=/home/${BUILD_USER}/GoogleDrive
PEGASE_SYNC_DIR=${DESIRED_SYNC_DIR}
PEGASE_BUILD_USER=${BUILD_USER}
PEGASE_PC_DEVICE_ID=${PC_ID}
EOF
  chmod 644 "${CONFIG}"
  echo "Config créée : ${CONFIG}"
else
  if grep -Fq 'pegase/sync' "${CONFIG}" 2>/dev/null; then
    sed -i "s|pegase/sync|${DRIVE_FOLDER}|g" "${CONFIG}"
    echo "Config migrée : pegase/sync → ${DRIVE_FOLDER}"
  fi
  if grep -Fq '/pegase/Pegase' "${CONFIG}" 2>/dev/null; then
    sed -i "s|/pegase/Pegase|/${DRIVE_FOLDER}|g" "${CONFIG}"
    echo "Config migrée : pegase/Pegase → ${DRIVE_FOLDER}"
  fi
  if ! grep -Fq 'PEGASE_SYNC_DIR=' "${CONFIG}" 2>/dev/null; then
    echo "PEGASE_SYNC_DIR=${DESIRED_SYNC_DIR}" >> "${CONFIG}"
    echo "PEGASE_SYNC_DIR ajouté à ${CONFIG}"
  fi
  echo "Config existante : ${CONFIG}"
fi

install -d -m 755 "${USER_UNIT_DIR}"
install -m 644 "${REPO}/scripts/${SERVICE_NAME}" "${USER_UNIT_DIR}/${SERVICE_NAME}"
install -m 644 "${REPO}/scripts/${TIMER_NAME}" "${USER_UNIT_DIR}/${TIMER_NAME}"
chown -R "${BUILD_USER}:${BUILD_USER}" "${USER_UNIT_DIR}"

sudo -u "${BUILD_USER}" env PEGASE_BUILD_USER="${BUILD_USER}" \
  bash "${REPO}/scripts/install-pegase-sync-drive-notepad.sh" --user-phase
