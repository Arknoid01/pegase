# Copilote Pégase — Livré ✅

**Statut :** mergé sur `main` (PR [#8](https://github.com/Arknoid01/pegase/pull/8) + [#9](https://github.com/Arknoid01/pegase/pull/9))  
**Test device :** smoke automatisé PASS — voir `docs/device-test-report-final.txt`  
**Post-merge :** `MediaProjectionCaptureService` (FGS Android 14+), orbe overlay vivante

---

## Fonctionnalités

| Domaine | Contenu |
|---------|---------|
| **Overlay** | Orbe 56dp + bulle messenger, chat, capture, mémorisation |
| **Architecture** | Process `:copilot`, Accessibility Service, whitelists strictes |
| **Actions locales** | YouTube sous-titres à la voix |
| **Traduction** | Overlay sur bounds a11y, cloud texte seul |
| **Notifications** | Whitelist + phrase Pégase (« Marine t'a écrit : … ») |
| **Surlignage** | Cadres éléments cliquables (a11y) |
| **OCR** | Fallback ML Kit si arbre a11y vide |
| **Apps** | Picker toute app installée |
| **Share** | Intent → mémoire / contexte nommé |

---

## Polish inclus

- Bulle welcome / streaming, double analyse, orbe clampée
- Permissions avec statut ✓, capture MediaProjection + FGS dédié
- Confirmations outils Oui/Non dans la bulle
- `strings_copilot.xml`, tests unitaires copilote

---

## Entrée utilisateur

**Outils → Mode copilote** — permissions : overlay, a11y, notifs, capture écran.

---

## Tests manuels restants (visuels)

1. YouTube whitelist → « active les sous-titres »
2. Chrome EN → traductions positionnées
3. WhatsApp notif → phrase Pégase dans la bulle
4. Share + « retiens ça »

---

*Copilote considéré terminé côté code — itérations futures = polish UX, pas de refonte architecture.*
