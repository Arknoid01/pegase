#!/usr/bin/env bash
# Migre les clés LLM depuis l'app Android (SharedPreferences) vers ~/.pegase/llm/keys.env
# Usage : ./scripts/import-llm-keys-from-phone.sh

set -euo pipefail

PKG="com.pegasuscorp.orbe"
PREFS="shared_prefs/orbe_api_keys.xml"
OUT="${HOME}/.pegase/llm/keys.env"

if ! command -v adb >/dev/null 2>&1; then
  echo "adb requis" >&2
  exit 1
fi
if ! adb get-state >/dev/null 2>&1; then
  echo "Téléphone non connecté (USB ou adb connect IP:5555)" >&2
  exit 1
fi

xml="$(adb shell "run-as ${PKG} cat ${PREFS}" 2>/dev/null)" || {
  echo "Impossible de lire ${PREFS} — app debuggable / run-as autorisé ?" >&2
  exit 1
}

pick() {
  python3 -c "
import re, sys
name = sys.argv[1]
text = sys.stdin.read()
m = re.search(rf'<string name=\"{re.escape(name)}\">([^<]*)</string>', text)
print((m.group(1) if m else '').strip())
" "$1" <<< "${xml}"
}

groq="$(pick groq_api_key)"
cerebras="$(pick cerebras_api_key)"
openrouter="$(pick openrouter_api_key)"

mkdir -p "$(dirname "${OUT}")"
umask 077
cat > "${OUT}" <<EOF
# Migré depuis l'app Android ($(date -Iseconds))
GROQ_API_KEY=${groq}
CEREBRAS_API_KEY=${cerebras}
OPENROUTER_API_KEY=${openrouter}
EOF
chmod 600 "${OUT}"

mask() {
  local v="$1"
  if [[ -z "${v}" ]]; then echo "(vide)"; return; fi
  echo "${v:0:8}…${v: -4}"
}

echo "Clés écrites dans ${OUT} (chmod 600)"
echo "  GROQ_API_KEY=$(mask "${groq}")"
echo "  CEREBRAS_API_KEY=$(mask "${cerebras}")"
echo "  OPENROUTER_API_KEY=$(mask "${openrouter}")"
