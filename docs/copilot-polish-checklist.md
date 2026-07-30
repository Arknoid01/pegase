# Copilote — Checklist polish

**Branche :** `cursor/v2-copilot-polish-14e9`  
**Base :** PR #8 (Phase 5 copilote)

## Bugs corrigés

- [x] Bulle welcome écrasée par le streaming LLM (`CopilotBubblePanel`)
- [x] Double analyse sur chaque changement a11y (`CopilotService`)
- [x] Orbe draggable hors écran (`FloatingOrbService`)
- [x] Orbe lancée sans permission overlay (`FloatingOrbService.showCopilot`)

## UX / permissions

- [x] Statut accordé / refusé sur les lignes permissions (`CopilotSettingsActivity`)
- [x] Ligne capture d'écran (vision / OCR)
- [x] Apps custom : bouton Retirer au lieu du switch ✕
- [x] État chargement dans le picker d'apps
- [x] Erreur traduction visible dans la bulle (`CopilotCloudBridge`)

## Cohérence visuelle

- [x] Tokens overlay dans `OrbeTokens` (bulle, chips, input)
- [x] `CopilotSettingsActivity` → couleurs `OrbeTokens`
- [x] Copy utilisateur : « Traduction à l'écran » (pas « overlay »)

## Robustesse

- [x] Surlignage boutons : debounce 4 s (`CopilotAnalysisEngine`)
- [x] Capture écran : déduplication des listeners permission
- [x] Helper overlay partagé (`BoundsOverlayHelper`)

## À faire (prochaines passes)

- [ ] Externaliser les strings copilote dans `strings.xml`
- [ ] Confirmations outils dans la bulle (`onConfirmNeeded`)
- [ ] IME_ACTION_SEND sur le champ message
- [ ] Tests : `CopilotNotificationFilter`, `OcrFallback`, bubble partial
- [ ] Merge PRs #4–#8 puis test device complet

## Test device rapide

1. Ouvrir bulle → envoyer message → welcome disparaît, partial n'écrase pas l'historique
2. Réglages copilote → permissions affichent ✓ ou « à accorder »
3. Chrome whitelist + page EN → traduction ; couper réseau → message d'erreur en bulle
4. Glisser l'orbe → reste dans l'écran
5. Surlignage ON → cadres apparaissent sans clignoter à chaque scroll
