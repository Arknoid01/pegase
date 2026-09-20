#!/usr/bin/env bash
# Sync bloc-notes + mémoires via Google Drive (~/GoogleDrive/Pegase).
#
# ARBORESCENCE (source : /etc/pegase/drive-sync.defaults.env + DriveSyncLayout.java)
#   ${DRIVE}/Pegase/android-<deviceId>/op-*.json   ← écrit par le téléphone (SAF)
#   ${DRIVE}/Pegase/pc-pegase-arch/op-*.json        ← écrit par ce script (export PC)
# Pas de sous-dossier intermédiaire "sync" — casse exacte "Pegase".
#
# SENS DE SYNC (ne pas inverser) :
#   tel → PC  : ce script IMPORTE en scannant android-*/ uniquement
#               → ~/.pegase/notepad/items.json, ~/.pegase/memory/permanent.json
#   PC → tel  : ce script EXPORTE les diffs locaux vers pc-pegase-arch/
#               → l'app Android importe pc-pegase-arch/ (Synchroniser maintenant)
#   Ce script ne lit jamais pc-pegase-arch/ (évite l'auto-import PC).

set -euo pipefail

DEFAULTS="${PEGASE_DRIVE_SYNC_DEFAULTS:-/etc/pegase/drive-sync.defaults.env}"
if [[ -f "${DEFAULTS}" ]]; then
  # shellcheck disable=SC1090
  source "${DEFAULTS}"
fi

CONFIG="${PEGASE_DRIVE_SYNC_CONFIG:-/etc/pegase/drive-sync.env}"
if [[ -f "${CONFIG}" ]]; then
  # shellcheck disable=SC1090
  source "${CONFIG}"
fi

PEGASE_HOME="${PEGASE_HOME:-/home/Pegase/.pegase}"
DRIVE="${PEGASE_DRIVE:-/home/Pegase/GoogleDrive}"
DRIVE_FOLDER="${PEGASE_DRIVE_FOLDER:-Pegase}"
SYNC_DIR="${PEGASE_SYNC_DIR:-${DRIVE}/${DRIVE_FOLDER}}"
NOTEPAD_FILE="${PEGASE_NOTEPAD_FILE:-${PEGASE_HOME}/notepad/items.json}"
MEMORY_FILE="${PEGASE_MEMORY_FILE:-${PEGASE_HOME}/memory/permanent.json}"
RECENT_TURNS_FILE="${PEGASE_RECENT_TURNS_FILE:-${PEGASE_HOME}/memory/recent_turns.json}"
CONVERSATION_META="${PEGASE_CONVERSATION_META:-${PEGASE_HOME}/sync/conversation-meta.json}"
CONVERSATION_SHADOW="${PEGASE_CONVERSATION_SHADOW:-${PEGASE_HOME}/sync/pc-conversation-shadow.json}"
STATE="${PEGASE_DRIVE_APPLIED:-${PEGASE_HOME}/sync/drive-applied-opids.json}"
NOTEPAD_SHADOW="${PEGASE_NOTEPAD_SHADOW:-${PEGASE_HOME}/sync/pc-notepad-shadow.json}"
MEMORY_SHADOW="${PEGASE_MEMORY_SHADOW:-${PEGASE_HOME}/sync/pc-memory-shadow.json}"
PC_EXPORT="${PEGASE_PC_EXPORT_STATE:-${PEGASE_HOME}/sync/pc-drive-export.json}"
PC_DEVICE_ID="${PEGASE_PC_DEVICE_ID:-pc-pegase-arch}"
BUILD_USER="${PEGASE_BUILD_USER:-Pegase}"

log() { printf '[pegase-drive-sync] %s\n' "$*"; }

if [[ "$(id -un)" != "${BUILD_USER}" ]]; then
  echo "Utilisateur attendu : ${BUILD_USER} (actuel : $(id -un))" >&2
  exit 1
fi

if ! command -v jq >/dev/null 2>&1; then
  echo "jq requis (pacman -S jq)" >&2
  exit 1
fi

if [[ ! -d "${DRIVE}" ]]; then
  echo "Google Drive introuvable : ${DRIVE}" >&2
  exit 1
fi

if ! mountpoint -q "${DRIVE}" 2>/dev/null; then
  echo "Google Drive non monté : ${DRIVE}" >&2
  exit 1
fi

mkdir -p "$(dirname "${NOTEPAD_FILE}")" "$(dirname "${MEMORY_FILE}")" \
  "$(dirname "${STATE}")" "${SYNC_DIR}" "${SYNC_DIR}/${PC_DEVICE_ID}"

if [[ ! -f "${NOTEPAD_FILE}" ]]; then
  echo '{"items":[]}' > "${NOTEPAD_FILE}"
fi

if [[ ! -f "${MEMORY_FILE}" ]]; then
  echo '[]' > "${MEMORY_FILE}"
fi

if [[ ! -f "${RECENT_TURNS_FILE}" ]]; then
  echo '[]' > "${RECENT_TURNS_FILE}"
fi

if [[ ! -f "${CONVERSATION_META}" ]]; then
  echo '{"updatedAtMs":0,"recordId":"recent"}' > "${CONVERSATION_META}"
fi

if [[ ! -f "${CONVERSATION_SHADOW}" ]]; then
  cp "${RECENT_TURNS_FILE}" "${CONVERSATION_SHADOW}"
fi

if [[ ! -f "${STATE}" ]]; then
  echo '{"appliedOpIds":[]}' > "${STATE}"
fi

if [[ ! -f "${NOTEPAD_SHADOW}" ]]; then
  cp "${NOTEPAD_FILE}" "${NOTEPAD_SHADOW}"
fi

if [[ ! -f "${MEMORY_SHADOW}" ]]; then
  cp "${MEMORY_FILE}" "${MEMORY_SHADOW}"
fi

if [[ ! -f "${PC_EXPORT}" ]]; then
  echo '{"nextSeq":1}' > "${PC_EXPORT}"
fi

apply_lww_delete() {
  local target_file="$1"
  local record_id="$2"
  local ts="$3"
  local local_ts tmp changed=0

  if jq -e 'type=="array"' "${target_file}" >/dev/null 2>&1; then
    local_ts="$(jq -r --arg id "${record_id}" '.[]? | select(.id==$id) | .updatedAtMs // 0' \
      "${target_file}" | head -n1)"
    local_ts="${local_ts:-0}"
    if [[ "${ts}" -ge "${local_ts}" ]]; then
      tmp="$(mktemp)"
      jq --arg id "${record_id}" '[.[] | select(.id != $id)]' "${target_file}" > "${tmp}"
      mv "${tmp}" "${target_file}"
      changed=1
    fi
  else
    local_ts="$(jq -r --arg id "${record_id}" '
      .items[]? | select(.id==$id) | .updatedAtMs // 0' "${target_file}" | head -n1)"
    local_ts="${local_ts:-0}"
    if [[ "${ts}" -ge "${local_ts}" ]]; then
      tmp="$(mktemp)"
      jq --arg id "${record_id}" '.items = [(.items // [])[] | select(.id != $id)]' \
        "${target_file}" > "${tmp}"
      mv "${tmp}" "${target_file}"
      changed=1
    fi
  fi
  echo "${changed}"
}

apply_lww_upsert() {
  local target_file="$1"
  local record_id="$2"
  local ts="$3"
  local payload="$4"
  local local_ts version merged tmp changed=0

  if jq -e 'type=="array"' "${target_file}" >/dev/null 2>&1; then
    local_ts="$(jq -r --arg id "${record_id}" '.[]? | select(.id==$id) | .updatedAtMs // 0' \
      "${target_file}" | head -n1)"
    local_ts="${local_ts:-0}"
    if [[ "${ts}" -ge "${local_ts}" ]]; then
      version="$(jq -r --argjson ts "${ts}" '[.updatedAtMs // 0, $ts] | max' <<<"${payload}")"
      merged="$(jq -c --argjson ver "${version}" '. + {updatedAtMs: $ver}' <<<"${payload}")"
      tmp="$(mktemp)"
      jq --arg id "${record_id}" --argjson item "${merged}" \
        '((. // []) | map(select(.id != $id))) + [$item]' "${target_file}" > "${tmp}"
      mv "${tmp}" "${target_file}"
      changed=1
    fi
  else
    local_ts="$(jq -r --arg id "${record_id}" '
      .items[]? | select(.id==$id) | .updatedAtMs // 0' "${target_file}" | head -n1)"
    local_ts="${local_ts:-0}"
    if [[ "${ts}" -ge "${local_ts}" ]]; then
      version="$(jq -r --argjson ts "${ts}" '[.updatedAtMs // 0, $ts] | max' <<<"${payload}")"
      merged="$(jq -c --argjson ver "${version}" '. + {updatedAtMs: $ver}' <<<"${payload}")"
      tmp="$(mktemp)"
      jq --arg id "${record_id}" --argjson item "${merged}" \
        '.items = ((.items // []) | map(select(.id != $id))) + [$item]' \
        "${target_file}" > "${tmp}"
      mv "${tmp}" "${target_file}"
      changed=1
    fi
  fi
  echo "${changed}"
}

apply_inbound_op() {
  local op_file="$1"
  local op_id store op record_id ts payload target_file changed=0 result

  op_id="$(jq -r '.opId // empty' "${op_file}")"
  [[ -n "${op_id}" ]] || return 0
  if jq -e --arg id "${op_id}" 'any(.appliedOpIds[]?; . == $id)' "${STATE}" >/dev/null 2>&1; then
    return 0
  fi

  store="$(jq -r '.store // empty' "${op_file}")"
  op="$(jq -r '.op // empty' "${op_file}")"
  record_id="$(jq -r '.recordId // empty' "${op_file}")"
  ts="$(jq -r '.tsMs // 0' "${op_file}")"

  mark_applied() {
    local tmp
    tmp="$(mktemp)"
    jq --arg id "${op_id}" '.appliedOpIds = ((.appliedOpIds // []) + [$id] | unique)' \
      "${STATE}" > "${tmp}"
    mv "${tmp}" "${STATE}"
  }

  case "${store}" in
    notepad) target_file="${NOTEPAD_FILE}" ;;
    memory) target_file="${MEMORY_FILE}" ;;
    conversation)
      payload="$(jq -c '.payload' "${op_file}")"
      remote_ts="$(jq -r '.tsMs // 0' "${op_file}")"
      local_ts="$(jq -r '.updatedAtMs // 0' "${CONVERSATION_META}")"
      if [[ "${remote_ts}" -gt "${local_ts}" ]]; then
        jq -c '.turns // []' <<< "${payload}" > "${RECENT_TURNS_FILE}.tmp"
        mv "${RECENT_TURNS_FILE}.tmp" "${RECENT_TURNS_FILE}"
        tmp="$(mktemp)"
        jq -n --argjson ts "${remote_ts}" '{updatedAtMs: $ts, recordId: "recent"}' > "${tmp}"
        mv "${tmp}" "${CONVERSATION_META}"
        changed=1
      fi
      mark_applied
      echo "${changed}"
      return 0
      ;;
    *) mark_applied; return 0 ;;
  esac

  if [[ "${op}" == "delete" ]]; then
    result="$(apply_lww_delete "${target_file}" "${record_id}" "${ts}")"
    changed="${result:-0}"
  elif [[ "${op}" == "upsert" ]]; then
    payload="$(jq -c '.payload' "${op_file}")"
    if [[ "${payload}" == "null" ]]; then
      mark_applied
      return 0
    fi
    result="$(apply_lww_upsert "${target_file}" "${record_id}" "${ts}" "${payload}")"
    changed="${result:-0}"
  fi

  mark_applied
  echo "${changed}"
}

write_pc_op() {
  local op_kind="$1"
  local store="$2"
  local record_id="$3"
  local ts_ms="$4"
  local payload="$5"
  local seq op_id op_file tmp

  seq="$(jq -r '.nextSeq // 1' "${PC_EXPORT}")"
  op_id="$(uuidgen 2>/dev/null || cat /proc/sys/kernel/random/uuid)"
  op_file="${SYNC_DIR}/${PC_DEVICE_ID}/op-$(printf '%06d' "${seq}").json"

  if [[ "${op_kind}" == "delete" ]]; then
    jq -n \
      --arg opId "${op_id}" \
      --argjson seq "${seq}" \
      --argjson tsMs "${ts_ms}" \
      --arg deviceId "${PC_DEVICE_ID}" \
      --arg store "${store}" \
      --arg recordId "${record_id}" \
      '{
        opId: $opId, seq: $seq, tsMs: $tsMs, deviceId: $deviceId,
        store: $store, op: "delete", recordId: $recordId, payload: null
      }' > "${op_file}"
  else
    jq -n \
      --arg opId "${op_id}" \
      --argjson seq "${seq}" \
      --argjson tsMs "${ts_ms}" \
      --arg deviceId "${PC_DEVICE_ID}" \
      --arg store "${store}" \
      --arg recordId "${record_id}" \
      --argjson payload "${payload}" \
      '{
        opId: $opId, seq: $seq, tsMs: $tsMs, deviceId: $deviceId,
        store: $store, op: "upsert", recordId: $recordId, payload: $payload
      }' > "${op_file}"
  fi

  tmp="$(mktemp)"
  jq --argjson next $((seq + 1)) '.nextSeq = $next' "${PC_EXPORT}" > "${tmp}"
  mv "${tmp}" "${PC_EXPORT}"
  echo 1
}

export_store_diff() {
  local store="$1"
  local current_file="$2"
  local shadow_file="$3"
  local exported=0 id item shadow_item ts result

  if jq -e 'type=="array"' "${current_file}" >/dev/null 2>&1; then
    while IFS= read -r item; do
      [[ -n "${item}" ]] || continue
      id="$(jq -r '.id' <<<"${item}")"
      shadow_item="$(jq -c --arg id "${id}" '.[]? | select(.id==$id)' "${shadow_file}" | head -n1)"
      if [[ -z "${shadow_item}" ]] || [[ "${item}" != "${shadow_item}" ]]; then
        ts="$(jq -r '.updatedAtMs // 0' <<<"${item}")"
        if [[ "${ts}" -le 0 ]]; then ts="$(date +%s000)"; fi
        if result="$(write_pc_op upsert "${store}" "${id}" "${ts}" "${item}")" \
          && [[ -n "${result}" ]]; then
          exported=$((exported + result))
        fi
      fi
    done < <(jq -c '.[]?' "${current_file}")

    while IFS= read -r id; do
      [[ -n "${id}" ]] || continue
      if ! jq -e --arg id "${id}" '.[]? | select(.id==$id)' "${current_file}" >/dev/null; then
        ts="$(date +%s000)"
        if result="$(write_pc_op delete "${store}" "${id}" "${ts}" "")" \
          && [[ -n "${result}" ]]; then
          exported=$((exported + result))
        fi
      fi
    done < <(jq -r '.[]?.id' "${shadow_file}")
  else
    while IFS= read -r item; do
      [[ -n "${item}" ]] || continue
      id="$(jq -r '.id' <<<"${item}")"
      shadow_item="$(jq -c --arg id "${id}" '.items[]? | select(.id==$id)' "${shadow_file}" | head -n1)"
      if [[ -z "${shadow_item}" ]] || [[ "${item}" != "${shadow_item}" ]]; then
        ts="$(jq -r '.updatedAtMs // 0' <<<"${item}")"
        if [[ "${ts}" -le 0 ]]; then ts="$(date +%s000)"; fi
        if result="$(write_pc_op upsert "${store}" "${id}" "${ts}" "${item}")" \
          && [[ -n "${result}" ]]; then
          exported=$((exported + result))
        fi
      fi
    done < <(jq -c '.items[]?' "${current_file}")

    while IFS= read -r id; do
      [[ -n "${id}" ]] || continue
      if ! jq -e --arg id "${id}" '.items[]? | select(.id==$id)' "${current_file}" >/dev/null; then
        ts="$(date +%s000)"
        if result="$(write_pc_op delete "${store}" "${id}" "${ts}" "")" \
          && [[ -n "${result}" ]]; then
          exported=$((exported + result))
        fi
      fi
    done < <(jq -r '.items[]?.id' "${shadow_file}")
  fi

  cp "${current_file}" "${shadow_file}"
  echo "${exported}"
}

export_conversation_diff() {
  local turns ts payload result exported=0
  if cmp -s "${RECENT_TURNS_FILE}" "${CONVERSATION_SHADOW}" 2>/dev/null; then
    echo 0
    return
  fi
  turns="$(jq -c '.' "${RECENT_TURNS_FILE}")"
  ts="$(jq -r '.updatedAtMs // 0' "${CONVERSATION_META}")"
  if [[ "${ts}" -le 0 ]]; then
    ts="$(date +%s000)"
  fi
  payload="$(jq -n --arg id recent --argjson tsMs "${ts}" --argjson turns "${turns}" \
    '{id: $id, updatedAtMs: $tsMs, turns: $turns}')"
  if result="$(write_pc_op upsert conversation recent "${ts}" "${payload}")" \
    && [[ -n "${result}" ]]; then
    exported="${result}"
  fi
  cp "${RECENT_TURNS_FILE}" "${CONVERSATION_SHADOW}"
  echo "${exported}"
}

applied=0
exported=0
shopt -s nullglob

# Import tel → PC : android-* uniquement (pc-pegase-arch lu par l'app Android).
for device_dir in "${SYNC_DIR}"/android-*; do
  [[ -d "${device_dir}" ]] || continue
  for op_file in "${device_dir}"/op-*.json; do
    [[ -f "${op_file}" ]] || continue
    if result="$(apply_inbound_op "${op_file}")" && [[ -n "${result}" ]]; then
      applied=$((applied + result))
    fi
  done
done

exported=$((exported + $(export_store_diff notepad "${NOTEPAD_FILE}" "${NOTEPAD_SHADOW}")))
exported=$((exported + $(export_store_diff memory "${MEMORY_FILE}" "${MEMORY_SHADOW}")))
exported=$((exported + $(export_conversation_diff)))

shopt -u nullglob
log "Terminé — ${applied} importée(s), ${exported} exportée(s) (${SYNC_DIR})"
