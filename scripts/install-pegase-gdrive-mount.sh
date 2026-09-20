#!/usr/bin/env bash
# Monte Google Drive sur ~/GoogleDrive au démarrage (systemd user + rclone).
# Usage : ./scripts/install-pegase-gdrive-mount.sh
#         sudo ./scripts/install-pegase-gdrive-mount.sh   # active aussi linger (boot sans session)

set -euo pipefail

BUILD_USER="${PEGASE_BUILD_USER:-Pegase}"
MOUNT_POINT="${PEGASE_DRIVE:-/home/${BUILD_USER}/GoogleDrive}"
REMOTE="${PEGASE_RCLONE_REMOTE:-gdrive:}"
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERVICE_NAME="pegase-gdrive-mount.service"
SERVICE_SRC="${REPO}/scripts/${SERVICE_NAME}"
USER_UNIT_DIR="/home/${BUILD_USER}/.config/systemd/user"
STATE_DIR="/home/${BUILD_USER}/.local/state/pegase"

log() { printf '[pegase-gdrive] %s\n' "$*"; }

if [[ "${EUID:-0}" -eq 0 && "$(id -un)" == "root" ]]; then
  if [[ ! -d "/home/${BUILD_USER}" ]]; then
    echo "Utilisateur introuvable : ${BUILD_USER}" >&2
    exit 1
  fi
  log "Activation linger pour ${BUILD_USER} (montage au boot)"
  loginctl enable-linger "${BUILD_USER}" 2>/dev/null || true
  exec sudo -u "${BUILD_USER}" env PEGASE_BUILD_USER="${BUILD_USER}" \
    PEGASE_DRIVE="${MOUNT_POINT}" PEGASE_RCLONE_REMOTE="${REMOTE}" \
    bash "${REPO}/scripts/install-pegase-gdrive-mount.sh"
fi

if [[ "$(id -un)" != "${BUILD_USER}" ]]; then
  echo "Utilisateur attendu : ${BUILD_USER} (actuel : $(id -un))" >&2
  echo "Relance : sudo $0" >&2
  exit 1
fi

if [[ ! -f "${HOME}/.config/rclone/rclone.conf" ]]; then
  echo "rclone non configuré : rclone config" >&2
  exit 1
fi

if ! rclone listremotes 2>/dev/null | grep -qx "${REMOTE}"; then
  echo "Remote rclone introuvable : ${REMOTE}" >&2
  echo "Remotes disponibles :" >&2
  rclone listremotes 2>&1 || true
  exit 1
fi

if [[ ! -f "${SERVICE_SRC}" ]]; then
  echo "Unité systemd manquante : ${SERVICE_SRC}" >&2
  exit 1
fi

mkdir -p "${USER_UNIT_DIR}" "${STATE_DIR}" "${MOUNT_POINT}"

if mountpoint -q "${MOUNT_POINT}" 2>/dev/null; then
  log "Déjà monté : ${MOUNT_POINT}"
else
  if [[ -n "$(ls -A "${MOUNT_POINT}" 2>/dev/null)" ]]; then
    STAGING="${HOME}/.local/state/pegase/gdrive-staging-$(date +%Y%m%d%H%M%S)"
    mkdir -p "${STAGING}"
    log "Sauvegarde du contenu local → ${STAGING}"
    shopt -s dotglob nullglob
    mv "${MOUNT_POINT}"/* "${STAGING}/" 2>/dev/null || true
    shopt -u dotglob nullglob
    if [[ -n "$(ls -A "${STAGING}" 2>/dev/null)" ]]; then
      log "Upload du contenu local vers ${REMOTE} (si absent sur Drive)"
      rclone copy "${STAGING}/" "${REMOTE}" --update --progress || true
    fi
  fi
fi

install -m 644 "${SERVICE_SRC}" "${USER_UNIT_DIR}/${SERVICE_NAME}"
systemctl --user daemon-reload
systemctl --user enable --now "${SERVICE_NAME}"

sleep 2
if mountpoint -q "${MOUNT_POINT}"; then
  log "Montage actif : ${MOUNT_POINT}"
  log "Cache rclone : dir-cache-time=10s poll-interval=10s (nouveaux fichiers visibles ~10s)"
  ls -la "${MOUNT_POINT}" | head -10
else
  echo "Échec montage — voir : journalctl --user -u ${SERVICE_NAME} -n 30" >&2
  exit 1
fi

echo ""
echo "=== Google Drive monté ==="
echo "Point de montage : ${MOUNT_POINT}"
echo "Cache listing : --dir-cache-time 10s --poll-interval 10s"
echo "Service : systemctl --user status ${SERVICE_NAME}"
echo "Redémarrer après mise à jour unité : systemctl --user restart ${SERVICE_NAME}"
echo "Logs : ${STATE_DIR}/rclone-gdrive.log"
echo "Test : cp fichier ${MOUNT_POINT}/ → visible sur Drive mobile"
