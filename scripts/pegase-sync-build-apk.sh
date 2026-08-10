#!/usr/bin/env bash
# Sync GitHub main → build APK debug → copie sur Google Drive.
# Lancé depuis Pégase (SSH pegase-remote) :
#   sudo -u Pegase /usr/local/bin/pegase-sync-build-apk.sh
#
# Config optionnelle : /etc/pegase/build-apk.env

set -euo pipefail

CONFIG="${PEGASE_BUILD_CONFIG:-/etc/pegase/build-apk.env}"
if [[ -f "${CONFIG}" ]]; then
  # shellcheck disable=SC1090
  source "${CONFIG}"
fi

REPO="${PEGASE_REPO:-/home/Pegase/Documents/orbe/orbe}"
BRANCH="${PEGASE_BRANCH:-main}"
DRIVE="${PEGASE_DRIVE:-/home/Pegase/GoogleDrive}"
APK_NAME="${PEGASE_APK_NAME:-app-debug.apk}"
BUILD_USER="${PEGASE_BUILD_USER:-Pegase}"

if [[ "$(id -un)" == "root" ]]; then
  echo "Ne pas lancer en root — utilise : sudo -u ${BUILD_USER} $0" >&2
  exit 1
fi

if [[ "$(id -un)" != "${BUILD_USER}" ]]; then
  echo "Utilisateur attendu : ${BUILD_USER} (actuel : $(id -un))" >&2
  exit 1
fi

APK_SRC="${REPO}/app/build/outputs/apk/debug/app-debug.apk"
APK_DEST="${DRIVE}/${APK_NAME}"

log() { printf '[pegase-build] %s\n' "$*"; }

if [[ ! -d "${REPO}/.git" ]]; then
  echo "Dépôt git introuvable : ${REPO}" >&2
  exit 1
fi

if [[ ! -d "${DRIVE}" ]]; then
  echo "Google Drive introuvable : ${DRIVE}" >&2
  exit 1
fi

log "Sync ${REPO} ← origin/${BRANCH}"
cd "${REPO}"
git fetch origin "${BRANCH}"
git reset --hard "origin/${BRANCH}"

log "Build Gradle (:app:assembleDebug)"
./gradlew :app:assembleDebug

if [[ ! -f "${APK_SRC}" ]]; then
  echo "APK absente après build : ${APK_SRC}" >&2
  exit 1
fi

mkdir -p "${DRIVE}"
cp -f "${APK_SRC}" "${APK_DEST}"
log "APK copiée → ${APK_DEST} ($(du -h "${APK_DEST}" | cut -f1))"
