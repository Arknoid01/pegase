# Copilote Pégase — Terminé

**Branche :** `cursor/v2-copilot-polish-14e9`  
**PRs :** [#8](https://github.com/Arknoid01/pegase/pull/8) (features) + [#9](https://github.com/Arknoid01/pegase/pull/9) (polish + finition)

---

## Fonctionnalités livrées

| Domaine | Contenu |
|---------|---------|
| **Overlay** | Orbe 56dp + bulle messenger, chat, capture écran, mémorisation |
| **Architecture** | Process `:copilot`, Accessibility Service, listes blanches strictes |
| **Actions locales** | YouTube sous-titres à la voix (sans cloud) |
| **Traduction** | Overlay positionné sur bounds a11y, cloud texte seul |
| **Notifications** | Whitelist + phrase Pégase (« Marine t'a écrit : … ») |
| **Surlignage** | Cadres sur éléments cliquables (a11y bounds) |
| **OCR** | Fallback ML Kit quand arbre a11y vide |
| **Apps** | Picker pour toute app installée (écran + notif) |
| **Share** | Intent partage → mémoire / contexte nommé |

---

## Polish & finitions

- Bugs bulle (welcome / streaming), double analyse, orbe hors écran
- Permissions avec statut ✓ / à accorder + capture MediaProjection
- Confirmations outils (Oui / Non) dans la bulle
- Strings externalisées (`strings_copilot.xml`)
- Tests unitaires : prefs, locale, translator, summarizer, highlights, notif filter, OCR
- Helper overlay partagé (`BoundsOverlayHelper`)

---

## Entrées utilisateur

1. **Orbe** — tap = bulle, long press = menu, drag = repositionner
2. **Réglages** — Outils → Mode copilote
3. **Permissions requises** — overlay, a11y (analyse/traduction), notifs (alertes), capture (vision/OCR)

---

## Test device (checklist finale)

| # | Test |
|---|------|
| 1 | Overlay + orbe visible hors Orbe |
| 2 | Bulle : message, streaming, confirmation outil |
| 3 | Réglages : permissions ✓, picker app custom |
| 4 | YouTube whitelist → « active les sous-titres » |
| 5 | Chrome EN → traductions positionnées |
| 6 | WhatsApp notif → « X t'a écrit : … » |
| 7 | Jeu/WebView vide → OCR si capture autorisée |
| 8 | Partager texte → Pégase / « retiens ça » |
| 9 | Mode voix → orbe grande (régression) |

---

## Merge suggéré

```
main ← #4 (personality) ← #5 (voice) ← #6/#7 (memory, utility) ← #8 (copilot) ← #9 (polish)
```

Ou squasher #8 + #9 en une seule PR copilote si préféré.

---

*Phase copilote considérée complète côté code — validation device à faire sur l'autre instance.*
