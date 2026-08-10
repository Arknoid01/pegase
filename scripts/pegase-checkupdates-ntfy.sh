#!/usr/bin/env bash
# Notification ntfy si des mises à jour Arch sont disponibles (checkupdates).
# Usage : pegase-checkupdates-ntfy.sh
# Config : /etc/pegase/ntfy.env (NTFY_TOPIC, NTFY_URL, NTFY_TOKEN optionnels)

set -euo pipefail

CONFIG="${PEGASE_NTFY_CONFIG:-/etc/pegase/ntfy.env}"
STATE_DIR="/var/lib/pegase"
SNOOZE_FILE="${STATE_DIR}/checkupdates-snooze-until"

if [[ -f "${CONFIG}" ]]; then
  # shellcheck disable=SC1090
  source "${CONFIG}"
fi

: "${NTFY_TOPIC:?NTFY_TOPIC manquant — voir /etc/pegase/ntfy.env}"

NTFY_BASE="${NTFY_URL:-https://ntfy.sh}"
NTFY_BASE="${NTFY_BASE%/}"
NTFY_SERVER="${NTFY_BASE}/${NTFY_TOPIC}"

if [[ -f "${SNOOZE_FILE}" ]]; then
  snooze_until="$(cat "${SNOOZE_FILE}" 2>/dev/null || echo 0)"
  now="$(date +%s)"
  if [[ "${snooze_until}" =~ ^[0-9]+$ ]] && (( now < snooze_until )); then
    exit 0
  fi
  rm -f "${SNOOZE_FILE}"
fi

if ! command -v checkupdates &>/dev/null; then
  echo "pacman-contrib requis (checkupdates)" >&2
  exit 1
fi

mapfile -t updates < <(checkupdates 2>/dev/null || true)
count="${#updates[@]}"
if (( count == 0 )); then
  exit 0
fi

preview="$(printf '%s\n' "${updates[@]}" | head -8)"
if (( count > 8 )); then
  preview="${preview}"$'\n'"… et $(( count - 8 )) autres"
fi

title="Mises à jour Arch (${count} paquets)"
body="${count} mise(s) à jour disponible(s) sur pegase-arch."$'\n\n'"${preview}"

urlencode() {
  python3 -c 'import urllib.parse,sys; print(urllib.parse.quote(sys.argv[1], safe=""))' "$1"
}

YES_URL="pegase://pc-shell/run?autoRunCommand=mise-a-jour-arch"
NO_URL="pegase://pc-shell/ntfy?action=dismiss"
LATER_URL="pegase://pc-shell/ntfy?action=snooze&hours=4&topic=$(urlencode "${NTFY_TOPIC}")&title=$(urlencode "${title}")&message=$(urlencode "${body}")"

if ! command -v jq &>/dev/null; then
  echo "jq requis pour les actions ntfy" >&2
  exit 1
fi

actions="$(jq -nc \
  --arg yes "${YES_URL}" \
  --arg no "${NO_URL}" \
  --arg later "${LATER_URL}" \
  '[
    {"id":"yes","action":"view","label":"Oui","url":$yes,"clear":true},
    {"id":"no","action":"view","label":"Non","url":$no,"clear":true},
    {"id":"later","action":"view","label":"Plus tard","url":$later,"clear":true}
  ]')"

curl_args=(
  -sfS
  -H "Title: ${title}"
  -H "Priority: default"
  -H "Tags: package,arrow_up"
  -H "Actions: ${actions}"
)

if [[ -n "${NTFY_TOKEN:-}" ]]; then
  curl_args+=(-H "Authorization: Bearer ${NTFY_TOKEN}")
fi

curl "${curl_args[@]}" -d "${body}" "${NTFY_SERVER}"
