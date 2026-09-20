#!/usr/bin/env bash
# Protocole E2E sync mémoires (tel ↔ PC), même logique que bloc-notes.
#
# Prérequis :
#   - Téléphone USB + Drive SAF configuré
#   - Google Drive monté sur le PC
#   - Script sync à jour : scripts/pegase-sync-drive-notepad.sh
#
# Usage :
#   ./scripts/validate-memory-sync-e2e.sh phone-add     # après ajout manuel sur tel
#   ./scripts/validate-memory-sync-e2e.sh pc-add        # pousse un souvenir depuis le PC
#   ./scripts/validate-memory-sync-e2e.sh phone-check   # vérifie import tel (après sync PC)
#   ./scripts/validate-memory-sync-e2e.sh full          # migration + PC-add + attente tel

set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
SYNC="${REPO}/scripts/pegase-sync-drive-notepad.sh"
MEMORY_PC="${PEGASE_MEMORY_FILE:-${HOME}/.pegase/memory/permanent.json}"
MARKER_PHONE="VALIDATION-TEL-$(date +%Y%m%d)"
MARKER_PC="VALIDATION-PC-$(date +%Y%m%d)"
DEVICE_ID="${PEGASE_ANDROID_DEVICE_ID:-android-f2e8961e545ccb14}"

log() { printf '[validate-memory] %s\n' "$*"; }
fail() { log "ÉCHEC — $*"; exit 1; }

require_adb() {
  adb get-state >/dev/null 2>&1 || fail "ADB indisponible"
}

require_drive() {
  [[ -x "${SYNC}" || -f "${SYNC}" ]] || fail "Script sync introuvable : ${SYNC}"
  DRIVE="${PEGASE_DRIVE:-${HOME}/GoogleDrive}"
  mountpoint -q "${DRIVE}" 2>/dev/null || fail "Drive non monté : ${DRIVE}"
}

run_pc_sync() {
  bash "${SYNC}"
}

phone_count() {
  adb shell run-as com.pegasuscorp.orbe cat files/memory/permanent.json \
    | jq 'length'
}

phone_has_marker() {
  local marker="$1"
  adb shell run-as com.pegasuscorp.orbe cat files/memory/permanent.json \
    | jq -e --arg m "${marker}" '.[] | select(.content | contains($m))' >/dev/null
}

pc_has_marker() {
  local marker="$1"
  jq -e --arg m "${marker}" '.[] | select(.content | contains($m))' "${MEMORY_PC}" >/dev/null
}

trigger_phone_sync() {
  printf '%s\n' \
    '{"lastExportedSeq":0,"lastSyncAttemptMs":0,"lastSyncAtMs":0,"lastSyncOk":true,"appliedRemoteOpIds":[]}' \
    | adb shell run-as com.pegasuscorp.orbe tee files/sync/drive_state.json >/dev/null
  adb shell am force-stop com.pegasuscorp.orbe
  adb shell am start -n com.pegasuscorp.orbe/.MainActivity >/dev/null
  log "Sync tel déclenché — attente 15s…"
  sleep 15
}

cmd_phone_add() {
  require_adb
  log "Ajoute sur le tel : Outils → 🎓 Pégase → Mémoire — réglages avancés → + Ajouter"
  log "Contenu suggéré : ${MARKER_PHONE} — souvenir test validation"
  read -r -p "Appuie Entrée quand c'est fait…"
  trigger_phone_sync
  require_drive
  run_pc_sync
  pc_has_marker "${MARKER_PHONE}" \
    && log "OK tel→PC — trouvé dans ${MEMORY_PC}" \
    || fail "Marker ${MARKER_PHONE} absent de ${MEMORY_PC}"
}

cmd_pc_add() {
  require_drive
  mkdir -p "$(dirname "${MEMORY_PC}")"
  [[ -f "${MEMORY_PC}" ]] || echo '[]' > "${MEMORY_PC}"
  local id ts content
  id="$(uuidgen)"
  ts="$(date +%s000)"
  content="${MARKER_PC} — souvenir test validation PC"
  tmp="$(mktemp)"
  jq --arg id "${id}" --argjson ts "${ts}" --arg content "${content}" \
    '. + [{
      id: $id, updatedAtMs: $ts, category: "user", content: $content,
      importance: 0.85, createdAt: "2026-08-10", source: "user",
      lastUsedAt: $ts, importanceAtLastUse: 0.85
    }]' "${MEMORY_PC}" > "${tmp}"
  mv "${tmp}" "${MEMORY_PC}"
  log "Souvenir PC ajouté (${content})"
  run_pc_sync
  log "Export PC fait — lance : $0 phone-check (téléphone connecté)"
}

cmd_phone_check() {
  require_adb
  trigger_phone_sync
  phone_has_marker "${MARKER_PC}" \
    && log "OK PC→tel — marker ${MARKER_PC} présent ($(phone_count) souvenirs)" \
    || fail "Marker ${MARKER_PC} absent sur le tel"
}

cmd_full() {
  bash "${REPO}/scripts/test-memory-migration.sh"
  cmd_pc_add
  require_adb && cmd_phone_check || log "Reconnecte le tel puis : $0 phone-check"
  log "Pour tel→PC : $0 phone-add"
}

case "${1:-help}" in
  phone-add) cmd_phone_add ;;
  pc-add) cmd_pc_add ;;
  phone-check) cmd_phone_check ;;
  full) cmd_full ;;
  *)
    sed -n '2,12p' "$0"
    exit 0
    ;;
esac
