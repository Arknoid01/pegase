# Phase 5 — Mode copilote : récap session

**Branche :** `cursor/v2-copilot-overlay-14e9`  
**PR :** [#8](https://github.com/Arknoid01/pegase/pull/8) (draft)  
**Base :** `main`  
**Date :** 30 juillet 2026

---

## Résumé exécutif

Cette session implémente **la Phase 5 de Pégase v2** : le **mode copilote**, un lien permanent entre toi et Pégase par-dessus toutes les apps. L’approche suit ta spec : construire sur l’existant (overlay, notifications, accessibility), **liste blanche stricte**, **maximum local**, cloud uniquement pour le texte utile (traduction).

Trois commits sur la branche :

| Commit | Contenu |
|--------|---------|
| `77d7dd2` | Orbe permanente + bulle messenger + capture écran |
| `e35d35f` | Architecture spec : a11y YouTube, process `:copilot`, Share Intent |
| `c9500c5` | Traduction overlay, cloud réel, notifs ciblées, UI réglages |

---

## Vision produit (spec retenue)

- **Orbe discrète** toujours visible (permission overlay explicite)
- **Bulle messenger** pour Q&A texte avec Pégase
- **Analyse d’écran continue** déclenchée par changement de contenu (pas d’intervalle fixe)
- **Liste blanche stricte** d’apps — jamais d’analyse par défaut (banque, etc.)
- **SCREEN_OFF** = analyse suspendue
- **Process `:copilot`** séparé et tuable (comme `:voice` pour le KWS)
- **Priorité arbre a11y** sur OCR ; cloud ne reçoit que le texte, pas l’image ni les positions
- **Premier cas d’usage hardcodé** : sous-titres YouTube à la voix (Accessibility Service, sans LLM)
- **Récupération texte web** : Partager → Pégase, ou copier + « retiens ça » / « ajoute ça à [contexte] »

---

## 1. Overlay orbe + bulle messenger

### Fichiers clés

| Fichier | Rôle |
|---------|------|
| `FloatingOrbService.java` | Dual-mode `VOICE` / `COPILOT`, orbe 56dp discrète |
| `CopilotBubblePanel.java` | UI messenger (messages, saisie, actions rapides) |
| `CopilotController.java` | Pont `PegaseSession` canal `COPILOT` |
| `CopilotPrefs.java` | Préférences (position orbe, bulle, listes blanches) |
| `ScreenCaptureHelper.java` | Capture MediaProjection |
| `ScreenCapturePermissionActivity.java` | Consentement capture écran |
| `ScreenCaptureTool.java` | Outil LLM `screen_capture` |
| `OpenRouterVisionClient.analyzeJpegBytes()` | Analyse vision cloud |

### Comportement

- **Tap** sur l’orbe → ouvre/ferme la bulle
- **Long-press** → menu (bulle, Pégase, activer/désactiver l’orbe)
- **Drag** → repositionnement mémorisé
- Actions bulle : **Écran** (capture + vision), **Retenir** (→ mémoire), **Ouvrir Pégase**
- Orbe masquée sur MainActivity / PegaseInterface ; visible ailleurs si `always_on`
- Démarrage au boot (`PrefetchBootReceiver`) et à chaque `onPause` hors Orbe

### Canal session

- Nouveau enum `Channel.COPILOT` dans `session/Channel.java`

---

## 2. Architecture copilote (spec détaillée)

### Accessibility Service — YouTube sous-titres

| Fichier | Rôle |
|---------|------|
| `PegaseAccessibilityService.java` | Service a11y, snapshot sur changement de contenu |
| `apps/YouTubeSubtitleAction.java` | Clic bouton CC/sous-titres (hardcodé, local) |
| `AccessibilityAccess.java` | Helper ouverture réglages a11y |
| `res/xml/pegase_accessibility_config.xml` | Config service |
| `CopilotActionTool.java` | Outil `copilot_action` pour le LLM |
| `CopilotIntentHandler.java` | Voix : « active les sous-titres » |

### Process `:copilot`

| Fichier | Rôle |
|---------|------|
| `CopilotService.java` | FGS analyse écran, SCREEN_ON/OFF |
| `CopilotClient.java` | Client IPC (pattern `VoiceWakeClient`) |
| `ICopilotService.aidl` / `ICopilotCallback.aidl` | API binder |
| `CopilotAnalysisEngine.java` | Pipeline analyse continue |
| `A11yTreeExtractor.java` | Extraction texte + bounds → JSON partagé |
| `ScreenContextStore.java` | Contexte écran local (prefs) |
| `ScreenTextExtractor.java` | OCR ML Kit (complément, `text-recognition`) |

### Règles d’analyse

- Déclenchement : `TYPE_WINDOW_CONTENT_CHANGED` / `TYPE_WINDOW_STATE_CHANGED` (debounce 280 ms)
- Filtre : `CopilotPrefs.isPackageAllowed()` — liste blanche vide par défaut
- Écran éteint : analyse suspendue dans `CopilotService`
- Snapshot : `files/copilot/a11y_snapshot.json` (partagé entre processus)

### Share Intent + clipboard

| Fichier | Rôle |
|---------|------|
| `ShareIngestActivity.java` | `ACTION_SEND text/plain` → Pégase |
| `ShareIngestRouter.java` | Route vers mémoire ou contexte nommé |

**Voix / clipboard :**

- « retiens ça » → `MemoryRepository` (mémoire permanente)
- « ajoute ça à Orion » → `ContextualFileStore` (fichier `.md` du contexte)

---

## 3. Traduction overlay positionnée

### Pipeline

```
a11y snapshot (texte + bounds JSON)
    → CopilotLocaleFilter (filtre langue par bloc, local)
    → CopilotAnalysisEngine détecte blocs étrangers
    → onCloudCandidate (IPC :copilot → main)
    → CopilotCloudBridge
    → CopilotTranslator (cloud : texte seul, format N|traduction)
    → TranslationOverlayService (labels aux coords left/top)
```

### Fichiers

| Fichier | Rôle |
|---------|------|
| `A11ySnapshot.java` | Modèle `Node` (texte + bounds) |
| `CopilotLocaleFilter.java` | Heuristique langue par bloc |
| `CopilotTranslator.java` | Appel LLM + parse réponse |
| `CopilotCloudBridge.java` | Pont main process |
| `TranslationOverlayService.java` | Overlay full-screen, labels positionnés |

### Principes respectés

- **Cloud** : reçoit uniquement le texte à traduire (pas l’image, pas les positions)
- **Overlay** : placement 100 % local via bounds du snapshot a11y
- Auto-masquage overlay traduction après **12 secondes**
- Toggle : `CopilotPrefs.isTranslationOverlayEnabled()` (défaut : activé)

---

## 4. Notifications ciblées

| Fichier | Rôle |
|---------|------|
| `CopilotNotificationFilter.java` | Filtre (liste blanche, pas group summary, contenu non vide) |
| `CopilotNotificationBridge.java` | Broadcast + affichage orbe |
| `PegaseNotificationListenerService` | `onNotificationPosted` branché |
| `CopilotController` | Récepteur → message `📩` dans la bulle |

- Liste blanche **séparée** de l’analyse écran (`notif_whitelist` dans prefs)
- Désactivé par défaut (`notif_copilot` = false)

---

## 5. UI réglages

**Chemin :** Pégase → Outils → **Mode copilote — réglages** (`CopilotSettingsActivity`)

### Toggles généraux

- Orbe toujours visible
- Analyse d’écran (apps autorisées)
- Overlay traduction
- Alertes notifications ciblées

### Listes blanches par app

Apps proposées : YouTube, Chrome, Firefox, Gmail, WhatsApp, Messages, Telegram, Slack

- Section **Apps — analyse d’écran**
- Section **Apps — notifications copilote**

### Raccourcis permissions

- Afficher par-dessus les apps
- Service d’accessibilité Pégase
- Accès aux notifications

---

## Arborescence des nouveaux fichiers

```
app/src/main/java/com/pegasuscorp/orbe/copilot/
├── AccessibilityAccess.java
├── A11ySnapshot.java
├── A11yTreeExtractor.java
├── CopilotAnalysisEngine.java
├── CopilotBubblePanel.java
├── CopilotClient.java
├── CopilotCloudBridge.java
├── CopilotController.java
├── CopilotLocaleFilter.java
├── CopilotNotificationBridge.java
├── CopilotNotificationFilter.java
├── CopilotPrefs.java
├── CopilotService.java          # process :copilot
├── CopilotSettingsActivity.java
├── CopilotTranslator.java
├── PegaseAccessibilityService.java
├── ScreenCaptureHelper.java
├── ScreenCapturePermissionActivity.java
├── ScreenContextStore.java
├── ScreenTextExtractor.java
├── ShareIngestActivity.java
├── ShareIngestRouter.java
├── TranslationOverlayService.java
└── apps/
    └── YouTubeSubtitleAction.java

app/src/main/java/com/pegasuscorp/orbe/tools/copilot/
└── CopilotActionTool.java

app/src/main/aidl/.../copilot/
├── ICopilotService.aidl
└── ICopilotCallback.aidl

app/src/test/.../copilot/
├── CopilotPrefsTest.java
├── CopilotAnalysisEngineTest.java
├── CopilotLocaleFilterTest.java
└── CopilotTranslatorTest.java
```

---

## Dépendances ajoutées

```gradle
implementation 'com.google.mlkit:text-recognition:16.0.1'  // OCR écran copilote
```

---

## Tests unitaires (Robolectric)

Exécutables localement si Android SDK / Gradle configuré :

```bash
./gradlew test --tests "com.pegasuscorp.orbe.copilot.*"
./gradlew test --tests "com.pegasuscorp.orbe.voice.handlers.CopilotIntentHandlerTest"
```

| Test | Vérifie |
|------|---------|
| `CopilotPrefsTest` | always_on, whitelist, position orbe |
| `CopilotAnalysisEngineTest` | parse contexte, « retiens ça », parseContextName |
| `CopilotLocaleFilterTest` | détection anglais vs français, filtre taille |
| `CopilotTranslatorTest` | parse réponse `N\|traduction` + bounds |
| `CopilotIntentHandlerTest` | détection phrases sous-titres YouTube |

---

## Plan de tests sur device

### Prérequis communs

1. Installer le build de la branche `cursor/v2-copilot-overlay-14e9`
2. **Paramètres Android → Orbe → Afficher par-dessus** → autoriser
3. Clé API OpenRouter configurée (pour vision + traduction cloud)

---

### A. Overlay orbe + bulle messenger

| # | Action | Résultat attendu |
|---|--------|------------------|
| A1 | Quitter Orbe (ouvrir Chrome) | Orbe discrète visible en bas à droite |
| A2 | Tap sur l’orbe | Bulle messenger s’ouvre |
| A3 | Envoyer un message texte | Pégase répond dans la bulle |
| A4 | Bouton **Écran** | Demande consentement capture → description de l’écran |
| A5 | Bouton **Retenir** | Texte mémorisé → vérifiable dans Mémoire |
| A6 | Long-press orbe → Désactiver | Orbe disparaît ; réactiver fonctionne |
| A7 | Retour sur Orbe (HOME) | Orbe masquée |
| A8 | Redémarrer le téléphone | Orbe réapparaît hors Orbe (si always_on) |

---

### B. Réglages copilote

| # | Action | Résultat attendu |
|---|--------|------------------|
| B1 | Pégase → Outils → **Mode copilote — réglages** | Écran réglages s’ouvre |
| B2 | Activer YouTube (analyse) | Package dans whitelist analyse |
| B3 | Activer WhatsApp (notif) | Package dans whitelist notifs |
| B4 | Liens permissions | Ouvre les bons écrans système |

---

### C. Accessibility — YouTube sous-titres

| # | Action | Résultat attendu |
|---|--------|------------------|
| C1 | Réglages → Accessibilité → **Pégase copilote** → activer | Service connecté |
| C2 | Ouvrir YouTube, lancer une vidéo | — |
| C3 | Dire « Pégase, active les sous-titres » | Sous-titres activés (ou bouton CC cliqué) |
| C4 | Sans a11y activée | Message demandant d’activer le service |

---

### D. Analyse d’écran continue

| # | Action | Résultat attendu |
|---|--------|------------------|
| D1 | App **non** dans whitelist (ex. banque) | Aucune analyse (pas de traduction) |
| D2 | App dans whitelist (ex. Chrome activé) | Snapshot a11y écrit dans `files/copilot/` |
| D3 | Éteindre l’écran | Analyse suspendue (log `SCREEN_OFF`) |
| D4 | Rallumer | Analyse reprise |

---

### E. Traduction overlay

| # | Action | Résultat attendu |
|---|--------|------------------|
| E1 | Chrome activé en whitelist + page **anglaise** | Labels traduction apparaissent sur le texte |
| E2 | Positions | Traductions alignées sur les blocs originaux (pas en vrac) |
| E3 | Après ~12 s | Overlay traduction disparaît |
| E4 | Désactiver « Overlay traduction » dans réglages | Plus d’overlay même si texte anglais |
| E5 | Page française | Pas de traduction (filtre local) |

---

### F. Share Intent + clipboard

| # | Action | Résultat attendu |
|---|--------|------------------|
| F1 | Sélectionner texte sur une page → **Partager → Pégase** | Toast confirmation + entrée en mémoire |
| F2 | Copier du texte → « Pégase, retiens ça » | Mémoire permanente mise à jour |
| F3 | Copier → « ajoute ça à orion » (si contexte existe) | Bloc ajouté au `.md` Orion |
| F4 | Presse-papiers vide + « retiens ça » | Message d’erreur explicite |

---

### G. Notifications ciblées

| # | Action | Résultat attendu |
|---|--------|------------------|
| G1 | Activer notifs copilote + WhatsApp en whitelist | — |
| G2 | Accès notifications Orbe accordé | — |
| G3 | Recevoir un message WhatsApp | Orbe apparaît + `📩` dans la bulle si ouverte |
| G4 | Notif d’une app **hors** whitelist | Ignorée par le copilote |
| G5 | Notifs copilote désactivées | Aucune alerte copilote |

---

### H. Régression voix / overlay vocal

| # | Action | Résultat attendu |
|---|--------|------------------|
| H1 | Discussion vocale active → quitter Orbe | Orbe **grande** (mode VOICE), tap → MainActivity |
| H2 | Pas de chat vocal + always_on | Orbe **petite** (mode COPILOT) |

---

## Ce qui reste pour plus tard

- Merge et test device des PRs #4–#9
- `open_url` minimal, browser complet (hors scope copilote)

## Livré depuis la spec initiale

- Résumé notif Pégase (`CopilotNotificationSummarizer`)
- OCR fallback (`OcrFallback`)
- Surlignage éléments (`ElementHighlightService`)
- Picker apps installées (`CopilotAppPickerActivity`)
- Polish complet (PR #9) — voir `docs/copilot-done.md`

---

## Merge suggéré

Cette PR est indépendante des PRs mémoire (#6) et utility actions (#7). Peut merger sur `main` après tests device.

**Ordre suggéré avec le reste du v2 :** #4 → #5 → #6 / #7 en parallèle → **#8 (cette PR)**

---

*Document généré à l’issue de la session Cloud Agent — branche `cursor/v2-copilot-overlay-14e9`.*
