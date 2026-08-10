#!/usr/bin/env bash
# Installe pegase-sync-build-apk.sh + sudoers pour pegase-remote.
# Usage : sudo ./scripts/install-pegase-sync-build-apk.sh

set -euo pipefail

REMOTE_USER="pegase-remote"
BUILD_USER="Pegase"
SCRIPT_NAME="pegase-sync-build-apk.sh"
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="${REPO}/scripts/${SCRIPT_NAME}"
DEST="/usr/local/bin/${SCRIPT_NAME}"
CONFIG_DIR="/etc/pegase"
CONFIG="${CONFIG_DIR}/build-apk.env"
SUDOERS="/etc/sudoers.d/pegase-remote"

if [[ "${EUID:-0}" -ne 0 ]]; then
  echo "Relance avec sudo : sudo $0" >&2
  exit 1
fi

if [[ ! -f "${SRC}" ]]; then
  echo "Script source introuvable : ${SRC}" >&2
  exit 1
fi

install -m 755 "${SRC}" "${DEST}"
echo "Installé : ${DEST}"

install -d -m 755 "${CONFIG_DIR}"
if [[ ! -f "${CONFIG}" ]]; then
  cat > "${CONFIG}" <<EOF
# Pégase — build APK → Google Drive
PEGASE_REPO=/home/Pegase/Documents/orbe/orbe
PEGASE_BRANCH=main
PEGASE_DRIVE=/home/Pegase/GoogleDrive
PEGASE_APK_NAME=app-debug.apk
PEGASE_BUILD_USER=${BUILD_USER}
EOF
  chmod 644 "${CONFIG}"
  echo "Config créée : ${CONFIG}"
else
  echo "Config existante conservée : ${CONFIG}"
fi

SUDO_LINE="${REMOTE_USER} ALL=(${BUILD_USER}) NOPASSWD: ${DEST}"
if [[ -f "${SUDOERS}" ]] && grep -Fq "${DEST}" "${SUDOERS}" 2>/dev/null; then
  echo "Sudoers déjà configuré pour ${DEST}"
else
  echo "" >> "${SUDOERS}"
  echo "# Build APK Pégase (Terminal PC)" >> "${SUDOERS}"
  echo "${SUDO_LINE}" >> "${SUDOERS}"
  chmod 440 "${SUDOERS}"
  visudo -cf "${SUDOERS}"
  echo "Sudoers mis à jour : ${SUDO_LINE}"
fi

echo ""
echo "=== Test (en tant que ${REMOTE_USER} via SSH) ==="
echo "  sudo -u ${BUILD_USER} ${DEST}"
echo ""
echo "Commande Pégase (intégrée au prochain ouverture Terminal PC) :"
echo "  sudo -u ${BUILD_USER} ${DEST}"
