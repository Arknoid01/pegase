# Pégase Desktop

Client Python pour Arch — chat LLM streaming + mémoire partagée avec le téléphone.

## Fonctionnalités

- **Chat streaming** token par token (Groq → Cerebras → OpenRouter)
- **Personnalité** `pegase-personality.md` + profil utilisateur
- **Contextes .md** attachés (`/load orion`, `/unload`, `/contexts`)
- **Atlas** entités depuis `~/.pegase/memory/entities.json`
- **Modes** `/mode normal|work|drive` (prompt + budget tokens)
- **Sync Drive** mémoire, bloc-notes, **historique chat** (`store: conversation`)

## Install

```bash
sudo pacman -S gtk4 libadwaita python-gobject   # UI GTK4
cd desktop
./scripts/install-pegase-desktop.sh
nano ~/.pegase/llm/keys.env   # GROQ_API_KEY=gsk_…
pegase-desktop
```

UI **GTK4** — panneau latéral overlay (420px) au-dessus des autres fenêtres. **Super+P** toggle · icône tray · style chat moderne.

```bash
sudo pacman -S gtk4 libadwaita python-gobject libappindicator-gtk3 gtk4-layer-shell
```

Réglages optionnels dans `~/.pegase/desktop-settings.json` :
`panel_width` (340–560), `panel_edge` (`left`|`right`), `panel_mode` (false = fenêtre classique).

## Fichiers clés

| Fichier | Rôle |
|---------|------|
| `~/.pegase/memory/recent_turns.json` | Historique chat (format Android) |
| `~/.pegase/memory/entities.json` | Atlas |
| `~/.pegase/contexts/*.md` | Contextes nommés |
| `~/.pegase/contexts/active.json` | Contextes chargés |
| `~/.pegase/mode.json` | Mode normal/work/drive |
| `~/.pegase/llm/keys.env` | Clés API |

## Sync chat tel↔PC

- **PC → tel** : export auto après chaque échange (`pc-pegase-arch/op-*.json`, `store: conversation`)
- **tel → PC** : timer script importe `android-*/` ; **tel** applique via sync app (nécessite build Android à jour)

```bash
# Timer PC (import android + export diffs)
/usr/local/bin/pegase-sync-drive-notepad.sh
```

Sur le tel : **Synchroniser maintenant** après mise à jour app.

## Commandes chat

`/memo` · `/note` · `/load` · `/unload` · `/contexts` · `/mode` · `/clear` · `/reload` · `/help`

## Dev

```bash
pip install -e .
python -m unittest discover -s tests
```
