# Pégase v2 — Résumé de session (grandes lignes)

**Repo :** [Arknoid01/pegase](https://github.com/Arknoid01/pegase)  
**Instance :** Cloud Agent (cette session)  
**Date :** juillet 2026

---

## Contexte

Travail parallèle sur **Pégase v2** (app `com.pegasuscorp.orbe`) : cette instance implémente, l'autre instance teste sur device. Branches au format `cursor/<nom>-14e9`.

---

## Déjà mergé sur `main`

- Correctifs diagnostic / trace (callbacks LLM stale, `HISTORY_SAFE`, troncature historique)

---

## PRs ouvertes (draft) — avant cette session

| PR | Branche | Contenu |
|----|---------|---------|
| [#4](https://github.com/Arknoid01/pegase/pull/4) | `cursor/v2-personality-guide-14e9` | Guide personnalité Pégase, `PersonalityGuide` |
| [#5](https://github.com/Arknoid01/pegase/pull/5) | `cursor/v2-voice-presence-14e9` | Budgets voix, TTS partiel, filtres phrases bannies |
| [#6](https://github.com/Arknoid01/pegase/pull/6) | `cursor/v2-memory-scoring-14e9` | Mémoire v2 : scoring, graphe entités, consolidation, UI 3D |
| [#7](https://github.com/Arknoid01/pegase/pull/7) | `cursor/v2-utility-actions-14e9` | Alarmes, timers, agenda (voix + outils) |

**Ordre de merge suggéré :** #4 → #5 → #6 et #7 en parallèle

---

## Cette session : Phase 5 — Mode copilote

**PR :** [#8](https://github.com/Arknoid01/pegase/pull/8) — `cursor/v2-copilot-overlay-14e9`

L'idée : une **orbe discrète toujours visible** par-dessus les autres apps, qui devient le lien permanent entre toi et Pégase.

### Ce qui a été fait (3 commits)

1. **Overlay de base** — orbe 56dp + bulle messenger, chat texte, capture écran / vision, mémorisation
2. **Architecture spec** — Accessibility Service (YouTube sous-titres à la voix), process `:copilot`, Share Intent, listes blanches strictes
3. **Finitions** — traduction overlay positionnée sur le texte, cloud (texte seul), notifs ciblées, écran réglages

### Principes retenus

- Liste blanche d'apps (rien par défaut sur une app non prévue)
- Analyse déclenchée par changement de contenu, pas en continu aveugle
- SCREEN_OFF = tout s'arrête
- Local d'abord (a11y, OCR) ; cloud uniquement pour le texte utile (ex. traduction)
- Partager → Pégase ou « retiens ça » pour la mémoire / les contextes

---

## Tests essentiels (device)

1. Permission **afficher par-dessus** + ouvrir une autre app → orbe visible
2. Tap orbe → bulle → envoyer un message
3. **Réglages copilote** (Outils) → activer YouTube + accessibilité Pégase
4. YouTube → « active les sous-titres »
5. Page anglaise dans Chrome → traductions sur le texte
6. Partager du texte → Pégase ; ou copier + « retiens ça »
7. Notif WhatsApp (si whitelist notifs activée) → alerte dans la bulle

---

## Docs dans le repo

| Fichier | Contenu |
|---------|---------|
| `docs/copilot-phase5-session.md` | Détail complet Phase 5 + plan de tests A→H |
| Ce fichier | Vue d'ensemble session |

---

## Pas encore fait (roadmap)

- Merge et test des PRs #4–#8
- `open_url` minimal, browser complet
- Externalisation strings copilote (`strings.xml`)
- Confirmations outils dans la bulle copilote

## Polish (PR dédiée)

Voir `docs/copilot-done.md` — copilote considéré **terminé** côté code (PR #9).
