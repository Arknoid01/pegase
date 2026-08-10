#!/usr/bin/env bash
# Installe le timer systemd checkupdates → ntfy pour Pégase.
# Usage : sudo ./scripts/install-pegase-checkupdates-timer.sh [chemin-repo]

set -euo pipefail

if [[ "${EUID:-0}" -ne 0 ]]; then
  echo "Relance avec sudo : sudo $0" >&2
  exit 1
fi

REPO="${1:-$(cd "$(dirname "$0")/.." && pwd)}"
SCRIPT_SRC="${REPO}/scripts/pegase-checkupdates-ntfy.sh"
SERVICE_SRC="${REPO}/scripts/pegase-checkupdates-ntfy.service"
TIMER_SRC="${REPO}/scripts/pegase-checkupdates-ntfy.timer"

for f in "${SCRIPT_SRC}" "${SERVICE_SRC}" "${TIMER_SRC}"; do
  if [[ ! -f "${f}" ]]; then
    echo "Fichier manquant : ${f}" >&2
    exit 1
  fi
done

echo "==> Paquets (pacman-contrib, jq, curl)"
pacman -Sy --needed --noconfirm pacman-contrib jq curl

echo "==> Répertoires d'état"
install -d -m 755 /etc/pegase
install -d -m 755 /var/lib/pegase

ENV_FILE="/etc/pegase/ntfy.env"
if [[ ! -f "${ENV_FILE}" ]]; then
  cat > "${ENV_FILE}" <<'EOF'
# Topic ntfy — abonne-toi avec l'app ntfy sur le téléphone.
NTFY_TOPIC=pegase-arch-updates
# NTFY_URL=https://ntfy.sh
# NTFY_TOKEN=   # si topic privé
EOF
  chmod 640 "${ENV_FILE}"
  echo "Config créée : ${ENV_FILE} — édite NTFY_TOPIC si besoin."
else
  echo "Config existante : ${ENV_FILE}"
fi

echo "==> Script + unités systemd"
install -m 755 "${SCRIPT_SRC}" /usr/local/bin/pegase-checkupdates-ntfy.sh
install -m 644 "${SERVICE_SRC}" /etc/systemd/system/pegase-checkupdates-ntfy.service
install -m 644 "${TIMER_SRC}" /etc/systemd/system/pegase-checkupdates-ntfy.timer

systemctl daemon-reload
systemctl enable --now pegase-checkupdates-ntfy.timer

echo ""
echo "=== Timer actif ==="
systemctl status pegase-checkupdates-ntfy.timer --no-pager || true
echo ""
echo "Test manuel : sudo /usr/local/bin/pegase-checkupdates-ntfy.sh"
echo "Sur Android : abonne l'app ntfy au topic $(grep -E '^NTFY_TOPIC=' "${ENV_FILE}" | cut -d= -f2-)"
echo "Action Oui → pegase://pc-shell/run?autoRunCommand=mise-a-jour-arch"
echo "Enregistre la commande intégrée « Mise à jour Arch » (sudo pacman -Syu) au premier ouverture du terminal."
