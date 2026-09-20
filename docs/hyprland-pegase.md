# Hyprland + Pégase Desktop

Installation **à côté de GNOME** (GDM inchangé). Phase A : intégration avec l’UI tkinter actuelle — pas de migration GTK4 ici.

## Prérequis

- Pégase installé : `./scripts/install-pegase-desktop.sh`
- Arch Linux, GDM + GNOME déjà en place

## Install

```bash
cd ~/Documents/orbe/orbe/desktop
chmod +x scripts/install-hyprland-pegase.sh scripts/pegase-hypr-toggle.sh
./scripts/install-hyprland-pegase.sh --packages
```

Sans `--packages` : déploie seulement `~/.config/hypr/` et `pegase-hypr-toggle`.

Avec `--force` : écrase les configs existantes (sinon skip + message diff).

### Paquets installés (`--packages`)

| Paquet | Rôle |
|--------|------|
| hyprland | Compositor / session Wayland |
| hyprpaper | Fond d’écran |
| waybar | Barre (bouton ✦ Pégase) |
| wofi | Lanceur d’apps |
| mako | Notifications |
| xdg-desktop-portal-hyprland | Portals (screenshot, etc.) |
| hyprpolkitagent | Dialogs polkit |

**Non touché** : GNOME, GDM, paquets GNOME.

`kitty` est déjà utilisé comme terminal (déjà installé chez toi).

## Utilisation

1. Déconnexion → session **Hyprland** au login GDM
2. **Super+P** — toggle Pégase (special workspace, flottant centré 520×740)
3. Clic **✦** sur Waybar — même action

Commande manuelle :

```bash
pegase-hypr-toggle
```

## Fichiers

| Fichier | Rôle |
|---------|------|
| `~/.config/hypr/hyprland.conf` | Config de base |
| `~/.config/hypr/hyprland-pegase.conf` | Window rules + bind Super+P |
| `~/.config/waybar/config.jsonc` | Barre avec module Pégase |
| `~/.local/bin/pegase-hypr-toggle` | Lance/focus Pégase + toggle workspace |

## WM_CLASS

La fenêtre tkinter expose `pegase-desktop` (voir `ui.py` + `StartupWMClass` dans le `.desktop`). Si les rules ne matchent pas :

```bash
hyprctl clients | grep -A5 pegase
```

## Phase B (plus tard)

Migration UI tkinter → GTK4/libadwaita : prompt séparé, puis mise à jour de `class:` dans `hyprland-pegase.conf`.
