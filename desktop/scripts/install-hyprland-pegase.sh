#!/usr/bin/env bash
# Hyprland à côté de GNOME + intégration Pégase (special workspace).
#
# Usage :
#   ./scripts/install-hyprland-pegase.sh              # configs + toggle seulement
#   ./scripts/install-hyprland-pegase.sh --packages # + pacman (demande confirmation)
#   ./scripts/install-hyprland-pegase.sh --force    # écrase configs existantes

set -euo pipefail

DESKTOP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SHARE_HYPR="${DESKTOP_DIR}/share/hypr"
HYPR_DIR="${HOME}/.config/hypr"
WAYBAR_DIR="${HOME}/.config/waybar"
BIN_DIR="${HOME}/.local/bin"

INSTALL_PACKAGES=0
FORCE=0

for arg in "$@"; do
  case "${arg}" in
    --packages) INSTALL_PACKAGES=1 ;;
    --force) FORCE=1 ;;
    -h|--help)
      sed -n '2,8p' "$0"
      exit 0
      ;;
    *)
      echo "Option inconnue : ${arg}" >&2
      exit 1
      ;;
  esac
done

log() { printf '[hyprland-pegase] %s\n' "$*"; }

install_file() {
  local src="$1" dest="$2" mode="${3:-644}"
  mkdir -p "$(dirname "${dest}")"
  if [[ -f "${dest}" && "${FORCE}" -eq 0 ]]; then
    if cmp -s "${src}" "${dest}"; then
      log "Identique : ${dest}"
      return 0
    fi
    log "Existe déjà (skip) : ${dest}"
    log "  diff : diff ${dest} ${src}"
    log "  ou   : $0 --force"
    return 0
  fi
  install -m "${mode}" "${src}" "${dest}"
  log "Installé : ${dest}"
}

deploy_configs() {
  install_file "${SHARE_HYPR}/hyprland.conf" "${HYPR_DIR}/hyprland.conf"
  install_file "${SHARE_HYPR}/hyprland-pegase.conf" "${HYPR_DIR}/hyprland-pegase.conf"
  install_file "${SHARE_HYPR}/hyprpaper.conf" "${HYPR_DIR}/hyprpaper.conf"
  install_file "${SHARE_HYPR}/waybar-config.jsonc" "${WAYBAR_DIR}/config.jsonc"

  mkdir -p "${BIN_DIR}"
  install_file "${DESKTOP_DIR}/scripts/pegase-hypr-toggle.sh" \
    "${BIN_DIR}/pegase-hypr-toggle" 755
}

install_pacman_packages() {
  local pkgs=(
    hyprland
    hyprpaper
    waybar
    wofi
    mako
    xdg-desktop-portal-hyprland
    hyprpolkitagent
  )
  local missing=()
  local p
  for p in "${pkgs[@]}"; do
    if ! pacman -Q "${p}" >/dev/null 2>&1; then
      missing+=("${p}")
    fi
  done
  if ((${#missing[@]} == 0)); then
    log "Paquets Hyprland déjà installés"
    return 0
  fi
  log "Paquets à installer : ${missing[*]}"
  log "GNOME/GDM ne seront pas désinstallés."
  read -r -p "Continuer avec sudo pacman -S ? [o/N] " ans
  case "${ans}" in
    o|O|y|Y|oui|yes)
      sudo pacman -S --needed "${missing[@]}"
      ;;
    *)
      log "Installation paquets annulée — configs déployées quand même"
      ;;
  esac
}

verify_gnome_sessions() {
  log "Sessions Wayland disponibles (GDM) :"
  ls -1 /usr/share/wayland-sessions/*.desktop 2>/dev/null | xargs -n1 basename || true
}

main() {
  if [[ ! -d "${SHARE_HYPR}" ]]; then
    echo "share/hypr manquant dans ${DESKTOP_DIR}" >&2
    exit 1
  fi

  if [[ ! -x "${HOME}/.local/bin/pegase-desktop" ]] \
     && [[ ! -x "${DESKTOP_DIR}/.venv/bin/pegase-desktop" ]]; then
    log "Pégase Desktop pas installé — lance d'abord ./scripts/install-pegase-desktop.sh"
  fi

  deploy_configs

  if [[ "${INSTALL_PACKAGES}" -eq 1 ]]; then
    install_pacman_packages
  else
    log "Paquets : relance avec --packages pour installer hyprland waybar wofi mako …"
  fi

  verify_gnome_sessions

  cat <<EOF

Prêt.
  1. Déconnexion GDM → choisir session « Hyprland »
  2. Super+P ou clic ✦ Waybar → toggle Pégase
  3. Test sans redémarrer (déjà en Hyprland) : pegase-hypr-toggle

GNOME intact : rien n'a été désinstallé. Retour possible via GDM → GNOME.

EOF
}

main "$@"
