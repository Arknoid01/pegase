# Inventaire wake → STT (scope détection / route / handoff)

Scope : détection wake word, source audio (BT vs téléphone), transition vers STT, interruptions/relances du cycle.  
Hors scope : TTS/haut-parleur (contenu Piper/Android TTS), sauf points où ils coupent/relancent l’écoute.

Lignes approximatives (état du code au moment de l’inventaire).

---

## 1. Fichiers / classes impliqués

### Process `:voice` (détection)

- `app/src/main/java/com/pegasuscorp/orbe/voice/VoiceService.java` — Service FGS du wake : choisit backend KWS/OWW/STT-duty, tient le hold SCO wake, détecte le mot-clé, lance `InPlaceVoiceActivity`.
- `app/src/main/java/com/pegasuscorp/orbe/voice/KwsAudioRouteManager.java` — Singleton **par process** : décide `RouteKind`, établit/relâche SCO/HFP/VR, expose `getAudioSource` / `preferredInput`, notifie les changements de route.
- `app/src/main/java/com/pegasuscorp/orbe/voice/SherpaKwsEngine.java` — Moteur Sherpa KWS (AudioRecord + decode) ; fire `Listener.onKeywordDetected` après filtres RMS/poche.
- `app/src/main/java/com/pegasuscorp/orbe/voice/OpenWakeWordEngine.java` — Moteur OWW/ORT alternatif ; même schéma AudioRecord + listener.
- `app/src/main/java/com/pegasuscorp/orbe/voice/KwsCrashGuard.java` — Compteur fails / désactivation KWS après morts rapides ; `onPlannedRestart` / `onKwsHealthy`.
- `app/src/main/java/com/pegasuscorp/orbe/voice/KwsDiagnostics.java` — Logs JSONL KWS (`kws_hit`, `kws_probe`, rejets RMS/poche).
- `app/src/main/java/com/pegasuscorp/orbe/voice/KwsModelStore.java` — Présence modèle Sherpa (gate `isModelReady`).
- `app/src/main/java/com/pegasuscorp/orbe/voice/KwsModelDownloader.java` — Téléchargement modèle ; callback re-start écoute dans `VoiceService.maybeAutoDownloadKws`.
- `app/src/main/java/com/pegasuscorp/orbe/voice/WakeOwwStore.java` — Pref OWW custom + readiness modèle OWW.
- `app/src/main/java/com/pegasuscorp/orbe/voice/PocketWakeGuard.java` — Capteur proximité ; `shouldSuppressWake` bloque le fire KWS.
- `app/src/main/java/com/pegasuscorp/orbe/voice/MediaPlaybackGuard.java` — Autre audio en lecture + throttle restarts STT duty-cycle ; mode gentle.
- `app/src/main/java/com/pegasuscorp/orbe/voice/WakeWordMatcher.java` — Matching/stripping wake dans flux STT Google (fallback duty-cycle).
- `app/src/main/aidl/com/pegasuscorp/orbe/voice/IVoiceWakeService.aidl` — API binder start/stop/pauseKeepSco/health.
- `app/src/main/aidl/com/pegasuscorp/orbe/voice/IWakeWordCallback.aidl` — Callback oneway `onWakeWordDetected`.
- `app/src/main/aidl/com/pegasuscorp/orbe/voice/IWakeHealthCallback.aidl` — Callback santé wake.
- `app/src/main/AndroidManifest.xml` (~L314–316) — Déclare `VoiceService` en `android:process=":voice"`.

### Process launcher (handoff UI / STT conversation)

- `app/src/main/java/com/pegasuscorp/orbe/voice/VoiceWakeClient.java` — Client binder launcher → `:voice` ; miroir `wantListen` / `keepScoWhilePaused` ; relay wake.
- `app/src/main/java/com/pegasuscorp/orbe/PegaseWakeService.java` — Façade `start`/`stop`/`pause`/`resume`/`sync` vers `VoiceWakeClient` (`pause` = `pauseKeepSco`).
- `app/src/main/java/com/pegasuscorp/orbe/voice/PegaseWakeController.java` — Flags launcher (chat/in-place/mute/bureau/…) ; `shouldListen` ; `resumeWakeIfAllowed` / `pauseWake`.
- `app/src/main/java/com/pegasuscorp/orbe/voice/PegaseWakeStore.java` — Prefs wake enabled / gentle / seuil OWW.
- `app/src/main/java/com/pegasuscorp/orbe/InPlaceVoiceActivity.java` — Activity overlay wake : `handleWakeIntent`, `moveTaskToBack`, cycle de vie in-place.
- `app/src/main/java/com/pegasuscorp/orbe/voice/VoiceInputHandler.java` — `proceedWakeActivation` / `handleWakeIntent` / `scheduleListeningResume*` / `exitChatMode` / `finalizeChatSession`.
- `app/src/main/java/com/pegasuscorp/orbe/voice/VoiceManager.java` — STT conversation (`SpeechRecognizer`) + hold SCO STT via **son** `KwsAudioRouteManager.getInstance` (process launcher).
- `app/src/main/java/com/pegasuscorp/orbe/voice/WakeToSttTrace.java` — Corrélation transition wake→STT (`begin`/`attachToIntent`/`adoptFromIntent`/`mark` → `PegaseDiagLog`).
- `app/src/main/java/com/pegasuscorp/orbe/chat/ChatVoiceBridge.java` — VoiceManager partagé ; `releaseSharedVoiceIfIdle` ; pause/resume wake autour interface.
- `app/src/main/java/com/pegasuscorp/orbe/chat/ChatSessionRegistry.java` — Singleton `ConversationManager` ; `isActive` / `finalizeSession`.
- `app/src/main/java/com/pegasuscorp/orbe/chat/ConversationManager.java` — Flag `active` enter/exit session chat (gate STT `conversation_inactive`).
- `app/src/main/java/com/pegasuscorp/orbe/session/LifecycleBridge.java` — Au retour HOME : `PegaseWakeService.sync` + éventuellement `resumeWakeIfAllowed` (`runDeferredWake`).
- `app/src/main/java/com/pegasuscorp/orbe/voice/WakeHealthEvaluator.java` / `WakeHealthStatus.java` / `WakeHealthUi.java` — Santé wake exposée UI.
- `app/src/main/java/com/pegasuscorp/orbe/voice/VoiceMuteStore.java` — Mute global → peut skip STT (`stt_schedule_skip` muted).
- `app/src/main/java/com/pegasuscorp/orbe/voice/SpeakerVerifyGate.java` — Gate locuteur optionnelle avant `proceedWakeActivation` (pause wake + reprise).
- `app/src/main/java/com/pegasuscorp/orbe/diag/PegaseDiagLog.java` — Persistance `kws_lifecycle.jsonl` (événements wake/SCO/STT).
- `app/src/main/java/com/pegasuscorp/orbe/FloatingOrbService.java` — `show`/`hide` autour session in-place (couplage UI, pas audio direct).

### Receivers / callbacks audio-BT (dans le scope)

- `KwsAudioRouteManager.registerObservers` (`~L1078`) — `AudioDeviceCallback` (add/remove devices) + `BroadcastReceiver` `ACTION_SCO_AUDIO_STATE_UPDATED` + `ACTION_HEADSET_PLUG`.
- `KwsAudioRouteManager.prepareCommunicationDeviceApi31` / `prepareScoLegacy` / `prepareViaHeadsetVoiceRecognition` — receivers **éphémères** SCO pendant les tentatives.
- `KwsAudioRouteManager.ensureHeadsetProxy` (`~L838`) — `BluetoothProfile.ServiceListener` HEADSET.
- `SherpaKwsEngine` / `OpenWakeWordEngine` — `RouteChangeListener` → `onExternalRouteChange` (flag `routeChanged`, sortie loop).
- `VoiceService` — `SherpaKwsEngine.Listener` / `OpenWakeWordEngine.Listener` + `onWakeAudioRouteChanged`.
- `app/src/main/java/com/pegasuscorp/orbe/intentions/IntentionEventReceiver.java` — ACL BT connect/disconnect (intentions voiture / learning) ; **ne pilote pas** SCO wake/STT (`ACTION_ACL_*` L25–72).

### Hors moteurs mais touchent start/stop wake

- `MainActivity.java` — `PegaseWakeService.sync` (L230) ; `voiceInput.exitChatMode` (L559) selon navigation.
- `PersonalizationPanel.java` — `PegaseWakeService.sync` après prefs wake/OWW (L352+).
- `iface/DiscussionFragment.java` — `pauseWake` / `resumeWakeIfAllowed`.
- `bureau/BureauActivity.java` / `BureauCanvasActivity.java` — pause/resume wake.
- `copilot/CopilotController.java` — `resumeWakeIfAllowed`.
- `WakeWordRecordActivity.java` — `pause`/`resume`/`sync` autour enregistrement wake.

### Non-cœur wake (référence seulement)

- `voice/AudioCapture.java` — capture générique ; pas le path KWS principal.
- `voice/SpeechOutput.java` — TTS (hors scope contenu) mais `VoiceManager.speak` appelle `stopListening`/`releaseSttScoAfterListen` avant parler.

---

## 2. États possibles et qui les modifie

### A. Process `:voice` — `VoiceService`

| État | Où | Qui écrit |
|------|-----|-----------|
| `wantListening` | `VoiceService` ~L76 | `startWakeListening` L103 ; `stopWakeListening` L113 ; `pauseWakeListeningKeepSco` L124 ; `onDestroy` L207 ; `onWakeDetected` L446 ; `maybeAutoDownloadKws` L325–333 |
| `listening` | ~L74 | `startKwsListenEngine` / STT duty / `stopListening*` / callbacks recognizer |
| `useKws` / `useOww` | ~L70–72 | `refreshWakeBackend` L234+ |
| `scoHoldPending` | ~L90 | `startKwsListen` ensure SCO async L672+ ; clear sur callback |
| `lastWakeDetectedMs` | ~L78 | `onWakeDetected` (debounce `WAKE_DEBOUNCE_MS`) |
| `kwsRestartStreak` / `sttBackoffStep` | ~L81–82 | health / erreurs STT duty |
| `lastHealth` | ~L88 | `refreshForegroundNotification` / health broadcast |
| Hold SCO timeout | `releaseKeptScoRunnable` L93–97 | posté par `pauseWakeListeningKeepSco` ; retiré par start/stop/destroy |

### B. Process `:voice` — `KwsAudioRouteManager` (instance `:voice`)

| État | Où | Qui écrit |
|------|-----|-----------|
| `activeKind` (`PHONE_BUILTIN` / `BLUETOOTH_SCO` / `WIRED_HEADSET` / `USB` / `UNKNOWN`) | ~L78 | `refreshRouteKind` L1195 ; `forcePhoneBuiltin` L174 |
| `preferredInput` | ~L79 | `refreshRouteKind` / succès SCO / `forcePhoneBuiltin` |
| `phoneForced` | ~L82 | `forcePhoneBuiltin` true ; `ensureBluetoothScoActive` / `ensureWakeServiceScoHold` / `rearm` remettent false |
| `scoPrepared` | ~L80 | chemins prepare* succès ; `releaseCaptureInternal` / `cleanupFailedScoAttemptLocked` |
| `scoHoldCount` | ~L70 | `ensureBluetoothScoActive` ++ ; `releaseBluetoothSco` -- |
| `wakeServiceHold` | ~L75 | `ensureWakeServiceScoHoldAsync` true ; `releaseWakeServiceScoHold` false |
| `bluetoothHeadset` / `headsetProxyRequesting` | ~L84–85 | `ensureHeadsetProxy` / disconnect / timeout retry |
| `voiceRecognitionActive` + `voiceRecognitionDevice` | ~L86–87 | `prepareViaHeadsetVoiceRecognition` / `stopHeadsetVoiceRecognition` |
| `lastScoFailReason` / phase / state logs | ~L95–97 | `establishScoWithFallbacksLocked` et sous-méthodes |
| `routeChangeListener` | ~L77 | `setRouteChangeListener` (écrasé par dernier moteur : OWW ou Sherpa) |
| Mode `AudioManager` / SCO on / communication device | système | prepare*/release* |

### C. Process launcher — `KwsAudioRouteManager` (autre instance)

- Même champs, **mémoire distincte** : utilisés par `VoiceManager` (`getInstance` L54) pour STT conversation.
- `scoHeldForStt` dans `VoiceManager` ~L47 — écrit `startRecognizerAfterRoute` / `releaseSttScoAfterListen`.

### D. Process launcher — `VoiceWakeClient`

| État | Où | Qui écrit |
|------|-----|-----------|
| `wantListen` | ~L31 | `startListening` L177 ; `stopListening` L197 ; `pauseKeepSco` L217 ; `handleWakeOnLauncher` L284 ; `sync` |
| `keepScoWhilePaused` | ~L32 | start/stop clear ; `pauseKeepSco` true |
| `remote` / `binding` | ~L29–30 | `ServiceConnection` |
| `gentle` | ~L33 | `setGentleMode` |
| `cachedHealth` | ~L34 | health callback |

### E. Process launcher — `PegaseWakeController` (statics)

| État | Qui écrit |
|------|-----------|
| `voiceChatActive` | `setVoiceChatActive` L134 ; aussi `setInPlaceVoiceActive` L65–68 |
| `inPlaceVoiceActive` | `setInPlaceVoiceActive` (`InPlaceVoiceActivity` onCreate/onDestroy ; `finalizeChatSession`) |
| `textDiscussionActive` | Discussion / `ChatVoiceBridge` |
| `pausedByUser` | `setPausedByUser` |
| `micGloballyMuted` | `VoiceMuteStore` / `setMicGloballyMuted` |
| `bureauActive` / `pushToTalkActive` | Bureau / PTT |
| `micListening` / `assistantThinking` / `wakeHealthProblem` | UI / health |

`shouldListen()` L40–47 = conjonction des négations de ces flags.

### F. Session / UI

| État | Qui écrit |
|------|-----------|
| `ConversationManager.active` | `enter` L95–96 ; `exit` L114–124 |
| `ChatSessionRegistry.conversation` | `get` / `finalizeSession` |
| `WakeToSttTrace` transition id/t0 (statics process) | `begin` / `adoptFromIntent` / `attachToIntent` |
| `KwsCrashGuard` prefs fails/start/config | `onKwsThreadDeath` / `onPlannedRestart` / `onKwsHealthy` / `resetForUser` / `bumpConfigGeneration` |
| `PegaseWakeStore` prefs enabled/gentle/threshold | settings / `applyStartupSafety` |
| `OpenWakeWordEngine.sLastFireElapsedMs` | fire OWW (réfractaire global) |
| `MediaPlaybackGuard.lastSttStartMs` / `gentle` | `markSttSessionStarted` / `setGentle` |
| `PocketWakeGuard` proximité near | SensorEventListener |

### G. Moteurs capture

| État | Classe | Qui écrit |
|------|--------|-----------|
| `wantRun` / `running` / `routeChanged` / `audioRecord` | Sherpa / OWW | `start`/`stop`/`loop`/`onExternalRouteChange` |
| `nativeBroken` | Sherpa | échecs natifs load/loop |

---

## 3. Événements / transitions (wake → écoute)

### Détection wake

- **Hit Sherpa** — `SherpaKwsEngine.loop` ~L355–359 → `Listener.onKeywordDetected` → `VoiceService` callback ~L287 → `onWakeDetected` L446.
- **Hit OWW** — `OpenWakeWordEngine.loop` ~L382 → `VoiceService` ~L261 → `onWakeDetected`.
- **Hit STT duty-cycle** (si pas de KWS local) — `SpeechRecognizer` partial/results `VoiceService` ~L365/L385 → `onWakeDetected`.
- **Rejet RMS / compound / poche** — Sherpa continue loop ; logs `KwsDiagnostics` ; pas de transition.
- **Debounce wake** — `onWakeDetected` si `< WAKE_DEBOUNCE_MS` → `scheduleListen(800)` si encore `wantListening`.

Actions `onWakeDetected` : `wantListening=false` ; `WakeToSttTrace.begin` ; `stopListeningForWakeTransition` ; `notifyWake` binder ; `launchInPlaceVoice`.

### Handoff launcher

- **`InPlaceVoiceActivity.onCreate`** L57–86 — `setInPlaceVoiceActive(true)` ; `wake_ui_opened` ; `handleWakeIntent` ; `moveTaskToBack` +350 ms.
- **`VoiceInputHandler.proceedWakeActivation`** ~L670 — `setVoiceChatActive(true)` ; `PegaseWakeService.pause` (= `pauseKeepSco`) ; `conversation.enter` ; TTS ack puis `scheduleListeningResumeAfterGreeting` → `VoiceManager.resumeListeningAfterReply`.
- **`VoiceManager.beginListeningWithBluetoothRoute`** L163 — `ensureBluetoothScoActiveAsync` (instance **launcher**) ; éventuellement `awaitScoReadyAsync(2200)` ; `SpeechRecognizer.startListening`.
- **STT skip** — `scheduleListeningResume` L1223 si `!conversation.isActive` → event `stt_schedule_skip` / `conversation_inactive`.

### SCO / BT / HFP

- **Ensure SCO wake** — `VoiceService.startKwsListen` → `ensureWakeServiceScoHoldAsync` L243+ ; diag `sco_service_start`.
- **Échec SCO ×2** — retry `WAKE_SCO_RETRY_DELAY_MS` ; si `no_hfp`/`a2dp_only` → `forcePhoneBuiltin` + start engine ; sinon `sco_bt_no_phone_fallback` + `scheduleListen(12000)` (code actuel).
- **ACL BT** — `IntentionEventReceiver` : pas de relance wake SCO.
- **Device add/remove** — `AudioDeviceCallback` → `onDevicesChanged` L1135 → éventuellement `RouteChangeListener` → `VoiceService.onWakeAudioRouteChanged` L422 → `stopKwsPlanned` + `refreshWakeBackend` + `scheduleListen(400)`.
- **SCO state broadcast** — `scoReceiver` / waiters → `onDevicesChanged("…SCO…")` ; peut être **suppressed** si hold stable L1155–1172.
- **HEADSET_PLUG** — même pipeline `onDevicesChanged`.
- **Headset proxy connect/disconnect** — `ServiceListener` L852–867 ; proxy null → `vr_no_headset_proxy`.
- **HFP devices empty** — `vr_no_hfp_device` L947.
- **Legacy CONNECTING→DISCONNECTED** — `legacy_CONNECTING_to_DISCONNECTED` etc.

### Pause / stop / resume écoute wake

- **`pauseWakeListeningKeepSco`** L121 — stop capture KWS, **garde** `wakeServiceHold` ; timeout 120 s relâche SCO.
- **`stopWakeListening`** L112 — stop + `releaseWakeServiceScoHold`.
- **`startWakeListening`** L102 — `wantListening=true` ; cancel timeout keep-SCO ; `scheduleListen`.
- **`PegaseWakeController.resumeWakeIfAllowed`** L168 — delay 8 s puis `VoiceWakeClient.startListening` si `shouldListen()`.
- **`PegaseWakeController.pauseWake`** L177 — `VoiceWakeClient.stopListening` (coupe SCO, distinct de `PegaseWakeService.pause`).
- **`PegaseWakeService.pause`** L30 — `VoiceWakeClient.pauseKeepSco` (garde SCO).
- **`PegaseWakeService.resume`** L35 — `startListening`.
- **`LifecycleBridge.runDeferredWake`** ~L209 — sync + éventuellement `resumeWakeIfAllowed`.

### Activity / UI

- **`moveTaskToBack`** — `InPlaceVoiceActivity` L86/L95.
- **`onDestroy` InPlace** L99 — `setInPlaceVoiceActive(false)` ; `releaseSharedVoiceIfIdle` ; **ne** `exitChatMode` **plus**.
- **`onBackPressed` / finish explicite** — `exitChatMode` L113 → `finalizeChatSession` L1309 : `conversation.exit`, `resumeWakeIfAllowed`, `stopListening`/`stopSpeaking` VoiceManager.
- **`onNewIntent`** L90 — nouveau wake sans recréer (flags SINGLE_TOP) ; re-`handleWakeIntent`.

### Focus / autre audio / erreurs moteur

- **Musique active** — `MediaPlaybackGuard.isOtherAudioPlaying` → `scheduleListen` poll (`startListenIfReady` / `startKwsListen`).
- **Audio focus SCO** — `requestScoAudioFocus` L793 ; abandon au release.
- **KWS thread mort / health** — `runKwsHealthCheck` ; `stopKwsPlanned` ; `KwsCrashGuard` ; éventuel disable KWS.
- **Route change mid-loop** — moteurs `routeChanged` → exit loop ; VoiceService watchdog relance.
- **STT error** (`VoiceManager` RecognitionListener) — `releaseSttScoAfterListen` ; `stt_error` ; `notifyListenFailed`.
- **Permission RECORD_AUDIO absente** — `startKwsListen` / STT return sans start.
- **Crash guard tripped** — `refreshWakeBackend` refuse KWS ; pas de STT duty-cycle (commentaire anti-kill micro).

### Binder / process

- **Service disconnect** — `VoiceWakeClient.onServiceDisconnected` ; rebind si `wantListen`.
- **onServiceConnected** — rejoue start / pauseKeepSco / stop selon flags client L65–70.

---

## 4. États partagés / sources de vérité contradictoires

- **Deux `KwsAudioRouteManager` distincts** (`getInstance` L100) : process `:voice` (wake) vs launcher (`VoiceManager` L54).  
  - `scoHoldCount` / `wakeServiceHold` / `scoPrepared` / `phoneForced` **non partagés**.  
  - SCO système est global, mais les ref-counts et diags divergent (`holds=1` voice vs `holds=0` au `stt_open_start` launcher).

- **`wantListening` (`VoiceService`) vs `wantListen` (`VoiceWakeClient`)** — miroirs IPC ; peuvent diverger si binder mort, callbacks manqués, ou `onWakeDetected` met `wantListening=false` sans sync client jusqu’à `pauseKeepSco`/`handleWakeOnLauncher`.

- **`keepScoWhilePaused` (client) vs `wakeServiceHold` (voice)** — client pense « keep » ; voice peut timeout 120 s (`releaseKeptScoRunnable`) ou `stopWakeListening` depuis un autre call-site (`pauseWake` vs `pause`).

- **`PegaseWakeController.shouldListen()` (launcher only)** — `:voice` **ne lit jamais** ces flags ; peut écouter alors que launcher croit chat actif, ou l’inverse après destroy activity.

- **`activeKind` / `wantsBluetoothMic()` vs HFP réel** — `refreshRouteKind` L1204–1171 peut forcer `BLUETOOTH_SCO` via `findBluetoothInputDevice` **ou** `hasBluetoothAudioOutput()` / `isScoLikelyAvailable()` même si `countHfpDevices()==0` → `want_bt=true` + `vr_no_hfp_device` / SCO fail.

- **`phoneForced` vs `wantsBluetoothMic()`** — `forcePhoneBuiltin` fige `PHONE_BUILTIN` jusqu’au prochain ensure qui clear `phoneForced` ; pendant ce temps `describeRoute` montre phone alors que BT device existe encore.

- **`routeChangeListener` unique** — `setRouteChangeListener` écrase ; si OWW et Sherpa coexistent en refresh, un seul reçoit les routes.

- **`ConversationManager.active` vs `voiceChatActive` vs `inPlaceVoiceActive`** — STT gate sur `conversation.isActive` ; wake gate sur `shouldListen` ; peuvent être désalignés (UI détruite, session encore active, ou l’inverse historique `exitChatMode` onDestroy).

- **`WakeToSttTrace` statics** — un seul transition id par process ; 2ᵉ wake / `onNewIntent` peut réécrire pendant que l’ancien STT log encore (`wake_ui_opened` avec mauvais `transition_id` observé).

- **`PegaseWakeService.pause` vs `pauseWake`** — pause façade = keep SCO ; `PegaseWakeController.pauseWake` = stop SCO ; call-sites mélangés (Discussion/Bureau vs wake activation).

- **`MediaPlaybackGuard.isOtherAudioPlaying` vs SCO IN_COMMUNICATION** — musique A2DP / focus peuvent bloquer start KWS alors que route dit BT.

- **`AudioManager.isBluetoothScoOn` / devices `TYPE_BLUETOOTH_SCO` vs `BluetoothHeadset.isAudioConnected`** — trois lectures utilisées à des endroits différents (`isScoLive`, `hasLiveBluetoothScoInput`, `isHeadsetAudioConnected`).

---

## 5. Couplages cycle audio ↔ cycle de vie UI / activity

- **`VoiceService.launchInPlaceVoice`** L489 — `startActivity(InPlaceVoiceActivity)` depuis FGS `:voice` (NEW_TASK | SINGLE_TOP | REORDER_TO_FRONT).
- **`InPlaceVoiceActivity.onCreate`** — crée `VoiceInputHandler` + bind session ; démarre handoff audio (`handleWakeIntent` → pause wake keep SCO → enter conversation → STT après ack).
- **`moveTaskToBack(true)`** L86/L95 — activity en arrière-plan ; OEM peut **détruire** l’activity ensuite.
- **`onDestroy`** L99 — `setInPlaceVoiceActive(false)` (peut impacter `shouldListen` via logique L65–68) ; `ChatVoiceBridge.releaseSharedVoiceIfIdle` (ne release que si `!ChatSessionRegistry.isActive()`).
- **`finalizeChatSession` / `exitChatMode`** L1304–1338 — seul chemin « propre » : coupe STT SCO (`voiceManager.stopListening`), relance wake (`resumeWakeIfAllowed` +8 s), `conversation.exit`, finish InPlace si besoin.
- **`onBackPressed`** L110 — `exitChatMode` + `finish` → enchaîne relance wake.
- **`ChatVoiceBridge.releaseSharedVoiceIfIdle`** — destruction UI peut tenter release `VoiceManager` (donc SCO STT / recognizer) si session inactive.
- **`LifecycleBridge` retour HOME** — `resumeWakeIfAllowed` / `sync` même pendant sessions si flags `shouldListen` true.
- **`MainActivity` / Discussion / Bureau / Copilot** — `pauseWake`/`resumeWakeIfAllowed` selon focus UI (coupent ou relancent le cycle détection indépendamment du KWS).
- **`SpeakerVerifyGate` path** — `PegaseWakeService.pause` puis éventuellement `resume` ; retarde `proceedWakeActivation`.
- **`FloatingOrbService.show/hide`** — lié à in-place / finalize ; pas d’audio direct mais signale session visuelle.
- **Manifest `launchMode="singleTask"` + `taskAffinity=""`** (`AndroidManifest.xml` ~L132–137) — recyclage task / reparenting OEM influence destroy/recreate et donc les hooks ci-dessus.
- **Deuxième wake pendant session** — `onNewIntent` re-`handleWakeIntent` sans forcément `conversation.exit` ; peut re-`pauseKeepSco` / re-ack / reprogrammer STT alors qu’un STT/TTS précédent vit encore sur `VoiceManager` partagé.

---

*Fin inventaire — aucun fichier applicatif modifié pour cette analyse (rapport seul).*
