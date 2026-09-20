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

Pipeline : **GitHub `main`** → `git reset --hard` → `./gradlew :app:assembleDebug` → copie vers **`/home/Pegase/GoogleDrive/app-debug.apk`** (montage rclone → cloud automatique).

### Montage Google Drive (Arch, une fois)

`~/GoogleDrive` est monté via **rclone** (`gdrive:`) au login et au boot :

```bash
cd /home/Pegase/Documents/orbe/orbe
chmod +x scripts/install-pegase-gdrive-mount.sh
./scripts/install-pegase-gdrive-mount.sh
# Boot sans session graphique :
sudo loginctl enable-linger Pegase
```

Prérequis : `rclone config` avec un remote `gdrive:` (ou `PEGASE_RCLONE_REMOTE`).

Vérif :

```bash
mountpoint ~/GoogleDrive
systemctl --user status pegase-gdrive-mount.service
```

Toute copie dans `~/GoogleDrive/` est envoyée sur Google Drive (cache VFS, quelques secondes).

### Build APK (script ou Cursor)

```bash
cd /home/Pegase/Documents/orbe/orbe
chmod +x scripts/install-pegase-sync-build-apk.sh scripts/pegase-sync-build-apk.sh
sudo ./scripts/install-pegase-sync-build-apk.sh
```

Test manuel :

```bash
sudo -u Pegase /usr/local/bin/pegase-sync-build-apk.sh
```

Prérequis : push/merge sur `main` du repo [pegase](https://github.com/Arknoid01/pegase.git) avant le build.

Après le build : ouvre **Google Drive** sur le téléphone → `app-debug.apk` → installer.

Config PC optionnelle : `/etc/pegase/build-apk.env`

Depuis Cursor : `./gradlew :app:assembleDebug` puis `cp app/build/outputs/apk/debug/app-debug.apk ~/GoogleDrive/app-debug.apk`.

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
| Drive vide / APK absente sur le tel | `mountpoint ~/GoogleDrive` ? `systemctl --user status pegase-gdrive-mount` ? boot : `sudo loginctl enable-linger Pegase` |
| Fichiers Drive absents localement (rclone) | `systemctl --user restart pegase-gdrive-mount` — cache listing 10s (`--dir-cache-time 10s`) |
| Sync : chemin incohérent | Dossier unique `~/GoogleDrive/Pegase/` (casse exacte) — voir `/etc/pegase/drive-sync.defaults.env` |
| PC→tel : ops non importées | Normal côté script bash (lit `android-*` seulement) ; import tel via app → **Synchroniser maintenant** |

## Sync Drive (bloc-notes + mémoires)

Source unique des chemins : `scripts/pegase-drive-sync.defaults.env` → `/etc/pegase/` (Android : `DriveSyncLayout.java`).

```
~/GoogleDrive/Pegase/
  android-<deviceId>/op-*.json    ← tel → PC (script systemd importe)
  pc-pegase-arch/op-*.json        ← PC → tel (app Android importe)
```

Install : `sudo ./scripts/install-pegase-sync-drive-notepad.sh` · Montage : `./scripts/install-pegase-gdrive-mount.sh`

## Pégase Desktop (Arch)

Client Python minimal — chat texte, mémoire partagée `~/.pegase/`. Voir `desktop/README.md` · install : `./desktop/scripts/install-pegase-desktop.sh`

## Fichiers côté Pégase (téléphone)

- `files/pc-shell/commands.json` — commandes enregistrées
- `files/pc-shell/executions.jsonl` — historique (50 dernières)
- Préférences chiffrées — host, port, user, clé privée

## Phase 2 (hors scope actuel)

- Outil LLM `pc_shell_run(commandId)` limité à la liste enregistrée
- Confirmation vocale avant exécution
