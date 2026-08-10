#!/usr/bin/env bash
# Setup PC Arch pour Terminal Pégase (Phase 1)
# Usage : sudo ./scripts/setup-pc-shell-arch.sh
#
# Prérequis : Arch Linux, accès root, connexion Internet.
# Après exécution : ajouter la clé publique Pégase (Réglages Terminal PC) dans authorized_keys.

set -euo pipefail

REMOTE_USER="pegase-remote"
SSH_PORT="22"
HOSTNAME_SUGGEST="pegase-arch"

if [[ "${EUID:-0}" -ne 0 ]]; then
  echo "Relance avec sudo : sudo $0" >&2
  exit 1
fi

echo "==> Paquets (openssh, tailscale)"
pacman -Sy --needed --noconfirm openssh tailscale

echo "==> Utilisateur dédié ${REMOTE_USER}"
if ! id "${REMOTE_USER}" &>/dev/null; then
  useradd -m -s /bin/bash -c "Pégase remote shell" "${REMOTE_USER}"
  echo "Utilisateur ${REMOTE_USER} créé."
else
  echo "Utilisateur ${REMOTE_USER} existe déjà."
fi

echo "==> Répertoire SSH"
install -d -m 700 -o "${REMOTE_USER}" -g "${REMOTE_USER}" "/home/${REMOTE_USER}/.ssh"
AUTH_KEYS="/home/${REMOTE_USER}/.ssh/authorized_keys"
if [[ ! -f "${AUTH_KEYS}" ]]; then
  install -m 600 -o "${REMOTE_USER}" -g "${REMOTE_USER}" /dev/null "${AUTH_KEYS}"
fi
echo "Fichier clés : ${AUTH_KEYS}"
echo "Colle-y la clé publique affichée dans Pégase (Terminal PC > Réglages)."

echo "==> sshd"
systemctl enable --now sshd

echo "==> Tailscale"
systemctl enable --now tailscaled
if ! tailscale status &>/dev/null; then
  echo ""
  echo "Tailscale pas encore authentifié. Lance :"
  echo "  sudo tailscale up --hostname=${HOSTNAME_SUGGEST}"
  echo "Puis note l'IP 100.x ou le hostname MagicDNS (${HOSTNAME_SUGGEST})."
else
  echo "Tailscale actif :"
  tailscale status || true
fi

echo "==> Exemple sudoers (commandes scoppées — à adapter)"
SUDOERS="/etc/sudoers.d/pegase-remote"
cat > "${SUDOERS}" <<'EOF'
# Terminal Pégase — NOPASSWD strict (une ligne par commande exacte).
# Édite avec visudo -f /etc/sudoers.d/pegase-remote
#
# Tests sans risque :
pegase-remote ALL=(ALL) NOPASSWD: /usr/bin/uptime
pegase-remote ALL=(ALL) NOPASSWD: /usr/bin/df
pegase-remote ALL=(ALL) NOPASSWD: /usr/bin/systemctl status sshd
#
# Mises à jour système (notification ntfy → action Oui) :
pegase-remote ALL=(ALL) NOPASSWD: /usr/bin/pacman
#
# Switch Windows (dual-boot) — adapter l'entrée GRUB à ta machine :
# pegase-remote ALL=(ALL) NOPASSWD: /usr/bin/grub-reboot Windows*
# pegase-remote ALL=(ALL) NOPASSWD: /usr/bin/systemctl reboot
#
# Alternative plus sûre : script wrapper unique
# pegase-remote ALL=(ALL) NOPASSWD: /usr/local/bin/pegase-reboot-windows
EOF
chmod 440 "${SUDOERS}"
visudo -cf "${SUDOERS}"

echo "==> Script wrapper reboot Windows (optionnel)"
WRAPPER="/usr/local/bin/pegase-reboot-windows"
cat > "${WRAPPER}" <<'EOF'
#!/usr/bin/env bash
# Reboot vers Windows — adapter GRUB_ENTRY à ton menu GRUB.
set -euo pipefail
GRUB_ENTRY="${PEGASE_GRUB_WINDOWS:-Windows Boot Manager}"
/usr/bin/grub-reboot "${GRUB_ENTRY}"
/usr/bin/systemctl reboot
EOF
chmod 755 "${WRAPPER}"
echo "Wrapper installé : ${WRAPPER}"
echo "Pour l'utiliser : décommente la ligne NOPASSWD du wrapper dans ${SUDOERS}"

echo ""
echo "=== Prochaines étapes ==="
echo "1. tailscale up --hostname=${HOSTNAME_SUGGEST}  (si pas fait)"
echo "2. Copie la clé publique Pégase dans ${AUTH_KEYS}"
echo "3. Dans Pégase : Réglages Terminal PC → hostname ${HOSTNAME_SUGGEST} ou IP Tailscale"
echo "4. Enregistre une commande test : uptime"
echo "5. Si sudo requis : ajoute la commande EXACTE dans ${SUDOERS}"
echo ""
echo "Sécurité : n'expose pas le port 22 sur Internet — Tailscale uniquement."
