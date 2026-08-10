#!/usr/bin/env bash
# Vérifie / répare l'appairage clé SSH Pégase ↔ pegase-remote
# Usage :
#   sudo ./scripts/check-pc-shell-pairing.sh
#   sudo ./scripts/check-pc-shell-pairing.sh 'ssh-ed25519 AAAA... pegase-android'

set -euo pipefail

REMOTE_USER="pegase-remote"
HOME_DIR="/home/${REMOTE_USER}"
SSH_DIR="${HOME_DIR}/.ssh"
AUTH_KEYS="${SSH_DIR}/authorized_keys"

if [[ "${EUID:-0}" -ne 0 ]]; then
  echo "Relance avec sudo." >&2
  exit 1
fi

echo "==> Compte ${REMOTE_USER}"
id "${REMOTE_USER}"

echo "==> Répertoire SSH"
if [[ ! -d "${SSH_DIR}" ]]; then
  echo "MANQUANT : ${SSH_DIR}"
  install -d -m 700 -o "${REMOTE_USER}" -g "${REMOTE_USER}" "${SSH_DIR}"
  echo "Créé."
else
  stat -c '%a %U:%G %n' "${SSH_DIR}"
fi

if [[ ! -f "${AUTH_KEYS}" ]]; then
  echo "MANQUANT : ${AUTH_KEYS}"
  install -m 600 -o "${REMOTE_USER}" -g "${REMOTE_USER}" /dev/null "${AUTH_KEYS}"
  echo "Fichier vide créé — il faut y coller la clé publique Pégase."
else
  echo "Présent : ${AUTH_KEYS}"
  stat -c '%a %U:%G %n' "${AUTH_KEYS}"
  lines=$(grep -cve '^\s*$' "${AUTH_KEYS}" || true)
  echo "Lignes non vides : ${lines}"
  if [[ "${lines}" -eq 0 ]]; then
    echo "⚠ authorized_keys est VIDE."
  else
    while IFS= read -r line; do
      [[ -z "${line// }" ]] && continue
      if [[ "${line}" == ssh-ed25519\ * ]]; then
        fp=$(echo "${line}" | awk '{print $2}' | head -c 20)
        echo "  OK  ssh-ed25519 …${fp}…"
      else
        echo "  ⚠ format suspect (doit commencer par ssh-ed25519) : ${line:0:60}…"
      fi
    done < "${AUTH_KEYS}"
    if command -v ssh-keygen >/dev/null 2>&1; then
      echo "Empreinte(s) ssh-keygen -lf :"
      ssh-keygen -lf "${AUTH_KEYS}" 2>/dev/null || true
    fi
  fi
fi

if [[ "${#}" -ge 1 && -n "${1:-}" ]]; then
  key="$1"
  if [[ "${key}" != ssh-ed25519\ * ]]; then
    echo "Clé refusée — attendu : ssh-ed25519 AAAA… pegase-android" >&2
    exit 1
  fi
  if grep -qF "${key}" "${AUTH_KEYS}" 2>/dev/null; then
    echo "Clé déjà présente — rien à faire."
  else
    echo "${key}" >> "${AUTH_KEYS}"
    chown "${REMOTE_USER}:${REMOTE_USER}" "${AUTH_KEYS}"
    chmod 600 "${AUTH_KEYS}"
    echo "Clé ajoutée."
  fi
fi

echo "==> sshd"
systemctl is-active sshd
ss -tlnp | grep ':22 ' || true

echo "==> Test local (sans clé client — doit refuser sauf si tu as une clé système)"
ssh -o BatchMode=yes -o StrictHostKeyChecking=no -o ConnectTimeout=3 \
  "${REMOTE_USER}@127.0.0.1" true 2>&1 | tail -1 || true

echo ""
echo "Sur le téléphone : Terminal PC > Réglages"
echo "  - Host : pegase-arch  (ou 100.97.80.33)"
echo "  - User : pegase-remote"
echo "  - Copie la clé publique et relance ce script avec l'argument."
