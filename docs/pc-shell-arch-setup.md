# Terminal PC — setup Arch (Phase 1)

Permet à Pégase (Android) d'exécuter des **commandes shell enregistrées** sur ce PC via SSH, **uniquement via Tailscale**, avec **clé Ed25519** et **lancement manuel** (tap + confirmation).

## Prérequis

- Arch Linux avec `sudo`
- Compte Tailscale (même tailnet que le téléphone)
- App Pégase avec la branche `feature/pc-shell-phase1` (ou plus récent)

## Installation rapide

```bash
cd /chemin/vers/pegase
chmod +x scripts/setup-pc-shell-arch.sh
sudo ./scripts/setup-pc-shell-arch.sh
sudo tailscale up --hostname=pegase-arch
```

## Appairage clé SSH

1. Sur le téléphone : **Interface Pégase → Outils → Terminal PC → Réglages**
2. Copie la **clé publique** (`ssh-ed25519 AAAA… pegase-android`)
3. Sur le PC, en tant que root ou avec sudo :

```bash
sudo -u pegase-remote bash -c 'cat >> ~/.ssh/authorized_keys'
# Colle la clé, puis Ctrl+D
sudo chmod 600 /home/pegase-remote/.ssh/authorized_keys
```

4. Dans Pégase, renseigne :
   - **Hostname** : `pegase-arch` (MagicDNS) ou l'IP Tailscale `100.x.x.x`
   - **Port** : `22`
   - **User** : `pegase-remote`

## Premières commandes à enregistrer (dans Pégase)

| Label | Commande | Sudo |
|-------|----------|------|
| Test connexion | `uptime` | non |
| Espace disque | `df -h` | non |
| Santé SSH | `systemctl status sshd` | non |

## Commandes avec sudo (scopées)

Le fichier `/etc/sudoers.d/pegase-remote` créé par le script n'autorise que des **chemins exacts**. Pour une nouvelle commande sudo :

```bash
sudo visudo -f /etc/sudoers.d/pegase-remote
```

Exemple — reboot vers Windows via wrapper :

```
pegase-remote ALL=(ALL) NOPASSWD: /usr/local/bin/pegase-reboot-windows
```

Puis enregistre dans Pégase :

```
/usr/local/bin/pegase-reboot-windows
```

**Ne jamais** accorder `NOPASSWD: ALL`.

## Switch Windows (GRUB)

1. Liste les entrées : `grep menuentry /boot/grub/grub.cfg`
2. Édite `/usr/local/bin/pegase-reboot-windows` et fixe `GRUB_ENTRY`
3. Autorise le wrapper dans sudoers (voir ci-dessus)

## Sécurité

- **Jamais** ouvrir le port 22 sur le routeur / IP publique
- Transport **Tailscale uniquement**
- Authentification **clé uniquement** (pas de mot de passe SSH pour `pegase-remote`)
- Phase 1 : **aucune** exécution automatique par le LLM

## Notifications ntfy — mises à jour Arch (checkupdates)

Timer systemd quotidien : si `checkupdates` trouve des paquets, envoi d'une notification **ntfy.sh** avec trois actions :

| Action | Comportement |
|--------|----------------|
| **Oui** | Ouvre Pégase → Terminal PC → biométrie → lance `sudo pacman -Syu` (slug `mise-a-jour-arch`) |
| **Non** | Ferme la notification |
| **Plus tard** | Rappel dans 4 h (alarme Android → republication ntfy) |

### Installation (Arch)

```bash
chmod +x scripts/install-pegase-checkupdates-timer.sh
sudo ./scripts/install-pegase-checkupdates-timer.sh
sudo nano /etc/pegase/ntfy.env   # NTFY_TOPIC=pegase-arch-updates
```

Sur le téléphone : app **ntfy** → s'abonner au même topic.

### Sudo pacman

Autoriser la catégorie pacman (pas une ligne par commande) :

```
pegase-remote ALL=(ALL) NOPASSWD: /usr/bin/pacman
```

La commande intégrée **Mise à jour Arch** (`mise-a-jour-arch`) est créée automatiquement au premier ouverture du terminal.

## Build APK → Google Drive

Pipeline : **GitHub `main`** → `git reset --hard` → `./gradlew :app:assembleDebug` → copie vers **`/home/Pegase/GoogleDrive/app-debug.apk`**.

### Installation (Arch, une fois)

```bash
cd /home/Pegase/Documents/orbe/orbe
chmod +x scripts/install-pegase-sync-build-apk.sh scripts/pegase-sync-build-apk.sh
sudo ./scripts/install-pegase-sync-build-apk.sh
```

Vérifie que Google Drive est monté/synchronisé (`/home/Pegase/GoogleDrive`).

Test manuel :

```bash
sudo -u Pegase /usr/local/bin/pegase-sync-build-apk.sh
```

### Depuis Pégase (téléphone)

Commande intégrée **Build APK (main → Drive)** (`build-apk`) — timeout 30 min.

Prérequis : push sur `main` du repo [pegase](https://github.com/Arknoid01/pegase.git) avant de lancer.

Après le build : ouvre **Google Drive** sur le téléphone → `app-debug.apk` → installer.

Config PC optionnelle : `/etc/pegase/build-apk.env`

### Test manuel

```bash
sudo /usr/local/bin/pegase-checkupdates-ntfy.sh
```

## Dépannage

| Symptôme | Piste |
|----------|-------|
| PC injoignable | Tailscale actif des deux côtés ? `tailscale status` |
| Clé refusée | Clé publique dans `authorized_keys` ? droits 600/700 ? |
| Permission denied (sudo) | Commande exacte dans sudoers ? |
| Timeout | PC en veille ? pare-feu local ? |

## Fichiers côté Pégase (téléphone)

- `files/pc-shell/commands.json` — commandes enregistrées
- `files/pc-shell/executions.jsonl` — historique (50 dernières)
- Préférences chiffrées — host, port, user, clé privée

## Phase 2 (hors scope actuel)

- Outil LLM `pc_shell_run(commandId)` limité à la liste enregistrée
- Confirmation vocale avant exécution
