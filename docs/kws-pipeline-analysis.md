# Analyse pipeline KWS — crash, faux négatifs, déclencheurs

> Branche de référence : `cursor/kws-pipeline-fix-14e9` (empilée sur diagnostics + route BT).
> Date : août 2026.

## Synthèse

Le seuil abaissé à **0.18** (vs 0.25) n'a pas restauré le taux de wake. Ce n'est donc pas qu'un problème de calibration : le pipeline audio redémarre trop souvent et parfois crashe en JNI pendant le teardown.

**Priorité proposée :**

1. **Race JNI** (`releaseCapture` / `prepareCapture` vs thread `AudioRecord.read`) — perte de service complète
2. **Redémarrages sans `onPlannedRestart`** — cascade `KwsCrashGuard` → KWS désactivé
3. **Mismatch SCO / modèle EN** — explique une partie du rate, pas le « quasi tout le temps » hors BT

Le crash **sans Bluetooth** (navigateur seul) confirme que l'hypothèse « uniquement changement route BT/SCO » est trop étroite.

---

## Cartographie des déclencheurs `routeChanged` / restart

### A. `KwsAudioRouteManager` → `onDevicesChanged`

| Source | Raison loggée | Déclenche restart KWS ? | Notes |
|--------|---------------|-------------------------|-------|
| `AudioDeviceCallback.onAudioDevicesAdded` | `"added"` | Si `activeKind` ou `preferredInput.id` change | **Tout** ajout de périphérique input (BT, USB, casque, parfois reroute interne OEM) |
| `AudioDeviceCallback.onAudioDevicesRemoved` | `"removed"` | Idem | Débranchement casque/BT/USB |
| Broadcast `ACTION_SCO_AUDIO_STATE_UPDATED` | action complète | Idem | CONNECTING / CONNECTED / DISCONNECTED / ERROR — **même sans changement de route effective** |
| Broadcast `ACTION_HEADSET_PLUG` | action complète | Idem | Branchement jack 3,5 mm |

**Bug critique (avant fix)** : `onDevicesChanged` appelait **`releaseCapture()` avant** de tester si la route avait changé. Un simple « route ping » SCO (CONNECTING puis CONNECTED, même device) coupait SCO/mode audio **pendant** que le thread KWS lisait `AudioRecord` → race JNI / `ERROR_DEAD_OBJECT`.

**Pas de listener** dans VoiceService/KWS pour : `SCREEN_ON`, `SCREEN_OFF`, audio focus, notification sonore. Ces événements peuvent quand même provoquer un `AudioDeviceCallback` ou un broadcast SCO sur certains OEM.

### B. `SherpaKwsEngine` — sortie de boucle

| Condition | `routeChanged` | Suite |
|-----------|----------------|-------|
| `VoiceService` / `stopListening()` → `kwsEngine.stop()` | `true` (via `stop`) | `join` thread → `closeMic` → `releaseCapture` |
| `onExternalRouteChange()` (route manager) | `true` | idem + callback `VoiceService.onKwsAudioRouteChanged` |
| `AudioRecord.read` ERROR_DEAD_OBJECT / INVALID_OP | non | break → restart via health check |
| `emptyStreak > 100` (buffers vides) | non | break → health check — **écran off / OEM suspend micro** |
| Mot-clé détecté | `wantRun=false` | arrêt normal |

### C. `VoiceService` — chemins `kwsEngine.stop()` / restart

| Chemin | `onPlannedRestart` | Redémarrage prévu | Risque crash guard |
|--------|-------------------|-------------------|-------------------|
| `onKwsAudioRouteChanged()` | ✅ | ~400 ms | OK |
| `runKwsHealthCheck()` restart #1–4 | ✅ | ~2,5 s | OK |
| `runKwsHealthCheck()` pause ×5 | ❌ → **fix** | 45 s | Faible (fenêtre 8 s) mais stop brutal inutile |
| `stopListening()` wake / user / destroy | ❌ (intentionnel) | Non si `wantListening=false` | OK |
| `maybeAutoDownloadKws` onComplete | ❌ → **fix** | ~800 ms | **Élevé** — stop/start rapide |
| `onDestroy()` | ❌ | Non | OK |

### D. Événements système **indirects** (pas de code dédié KWS)

| Événement | Mécanisme probable | Observé ? |
|-----------|-------------------|-----------|
| Navigateur / autre app audio | `AudioDeviceCallback`, mode audio, micro partagé | Crash sans BT signalé |
| Notification / son court | `isOtherAudioPlaying` → skip frames, pas teardown | Poll média 4 s |
| App au premier plan | Reroute communication device (API 31+) | Possible via callback |
| Écran off/on | Wake lock PARTIAL tient CPU ; micro OEM peut renvoyer buffers vides → `emptyStreak` | Pas de SCREEN_* dans VoiceService |
| Téléchargement modèle KWS | `stopListening` + `refreshWakeBackend` | Restart rapide sans planned |

---

## Race JNI — mécanisme

```
Thread KWS (sherpa-kws)          Main / RouteManager
─────────────────────           ───────────────────
audioRecord.read()  ◄────────── onDevicesChanged()
       │                        releaseCapture()  ← coupe SCO/mode
       │                        (SCO actif, record ouvert)
       ▼
ERROR_DEAD_OBJECT / crash JNI
```

`prepareCapture()` sur le **même** thread KWS dans `openMic()` est sûr. `releaseCapture()` depuis le main handler du route manager **pendant** une session active ne l'est pas.

**Correctif** : ne libérer SCO/mode que depuis `closeMic()` (thread KWS), après sortie de la boucle. Le route manager ne fait plus que détecter le changement et notifier.

---

## `KwsCrashGuard` — cascade

- Fenêtre : mort < **8 s** après `onKwsStarting` → `fail++`
- Seuil : **5** échecs → KWS désactivé (`shouldDisableKws`)
- `onPlannedRestart` remet `start_ms=0` pour ne pas compter un restart volontaire

Sans `onPlannedRestart` sur `maybeAutoDownloadKws`, un cycle stop → restart en < 8 s ressemble à un crash natif → accumulation vers coupure totale du wake.

---

## Modèle / calibration (priorité 3)

- Modèle : zipformer2 GigaSpeech **EN**, mot-clé BPE « Pégase » FR
- Seuil : 0.18 — insuffisant seul si le micro est coupé/redémarré en boucle
- SCO + `VOICE_COMMUNICATION` vs téléphone `MIC` : décalage qualité signal, pas la cause principale du rate « quasi permanent »

---

## Correctifs implémentés (`cursor/kws-pipeline-fix-14e9`)

1. **`KwsAudioRouteManager`** : suppression de `releaseCapture()` dans `onDevicesChanged` ; debounce 300 ms des notifications ; log `route_ping` vs `route_changed`
2. **`SherpaKwsEngine`** : verrou capture (`captureLock`) — `prepareCapture` / `releaseCapture` uniquement depuis le thread KWS ; `stop()` n'écrase plus `routeChanged` si déjà signalé pour route
3. **`VoiceService`** : helper `stopKwsPlanned()` avec `onPlannedRestart` ; utilisé sur download modèle et health pause

---

## Validation device recommandée

Exporter `files/diag/kws_lifecycle.jsonl` après session et corréler :

- `audio_route_changed` / `kws_route_changed` / `route_ping`
- `crash_guard_quick_death` vs `crash_guard_planned_restart`
- `kws_listen_start` / `kws_listen_stop` rapprochés (< 2 s)
- crash natif dans `crashes.jsonl`

Scénarios : BT on/off, navigateur seul, écran off 30 s, notification sonore, branchement jack.
