package com.pegasuscorp.orbe.voice;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.MessageQueue;
import android.os.PowerManager;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import com.pegasuscorp.orbe.diag.PegaseDiagLog;

import org.json.JSONObject;

import java.util.ArrayList;

/**
 * Wake word uniquement — processus {@code :voice}.
 * <p>
 * Sherpa KWS par défaut ; openWakeWord seulement si {@link WakeOwwStore#preferCustomWake}.
 * Pas de STT en boucle (OEM kill micro). Start/stop via binder.
 */
public class VoiceService extends Service {

    public static final String ACTION_BIND = "com.pegasuscorp.orbe.voice.BIND";

    private static final String TAG = "VoiceService";
    private static final String CHANNEL_ID = "pegase_voice_wake_min";
    private static final int NOTIF_ID = 78;
    private static final long MEDIA_PAUSE_POLL_MS = 8_000L;
    private static final long START_LISTEN_DELAY_MS = 800L;
    private static final long WAKE_DEBOUNCE_MS = 8_000L;
    private static final long KWS_MEDIA_POLL_MS = 4_000L;
    /** Si le thread KWS meurt sans mot-clé, relancer tant que wantsListening. */
    private static final long KWS_HEALTH_FIRST_MS = 4_000L;
    private static final long KWS_HEALTH_PERIOD_MS = 30_000L;
    private static final long KWS_HEALTH_GRACE_MS = 3_500L;
    /** STT en arrière-plan = ouvre/ferme le micro → OEM tue le service. Backoff fort. */
    private static final long[] STT_BACKOFF_MS = { 20_000L, 45_000L, 90_000L };

    private final Handler main = new Handler(Looper.getMainLooper());
    private final RemoteCallbackList<IWakeWordCallback> callbacks = new RemoteCallbackList<>();
    private final RemoteCallbackList<IWakeHealthCallback> healthCallbacks =
            new RemoteCallbackList<>();

    private SpeechRecognizer recognizer;
    private KwsAudioRouteManager kwsRouteManager;
    private AudioRouteObserver audioRouteObserver;
    private WakeCoordinator wakeCoordinator;
    private SherpaKwsEngine kwsEngine;
    private OpenWakeWordEngine owwEngine;

    private boolean idleListenPending;
    private long lastWakeDetectedMs;
    private long lastKwsStartMs;
    private MessageQueue.IdleHandler pendingIdleHandler;
    private int kwsRestartStreak;
    private int sttBackoffStep;
    /** CPU awake pendant l'écoute (écran off) — évite faux buffers silencieux. */
    private PowerManager.WakeLock listenWakeLock;
    /** Empreinte notif FGS — un seul startForeground tant que le texte ne change pas. */
    private String lastNotifFingerprint;
    private boolean foregroundStarted;
    private WakeHealthStatus lastHealth = WakeHealthStatus.OFF;
    /** Garde réentrance async SCO wake (pas une source de vérité d'état). */
    private boolean wakeScoAcquireInFlight;
    /** STT duty-cycle (fallback rare) — distinct du KWS local. */
    private boolean sttRecognizerActive;
    private WakeCoordinator.WakeState lastCoordinatorState = WakeCoordinator.WakeState.IDLE;
    /** Sécurité : ne pas garder le SCO indéfiniment après pause conversation. */
    private static final long KEEP_SCO_TIMEOUT_MS = 120_000L;
    private final Runnable releaseKeptScoRunnable = () -> {
        if (!wantsListening()) {
            Log.i(TAG, "keep-SCO timeout — release");
            releaseWakeServiceScoHold();
        }
    };

    private final IVoiceWakeService.Stub binder = new IVoiceWakeService.Stub() {
        @Override
        public void startWakeListening() {
            sttBackoffStep = 0;
            kwsRestartStreak = 0;
            main.removeCallbacks(releaseKeptScoRunnable);
            if (wakeCoordinator != null && wakeCoordinator.start()) {
                ensureSessionEngines();
            }
            refreshForegroundNotification();
            if (wantsListening()) {
                scheduleListen(START_LISTEN_DELAY_MS);
            }
        }

        @Override
        public void stopWakeListening() {
            // Pendant tout le handoff (wake détecté → STT ouvert → phrase finie) le hold
            // SCO porte la route casque : le lâcher ici fait retomber le STT sur le micro
            // téléphone (holds 1→0, phoneForced).
            WakeCoordinator.WakeState st = wakeCoordinator != null
                    ? wakeCoordinator.getState().state : WakeCoordinator.WakeState.IDLE;
            boolean handoffInFlight = st == WakeCoordinator.WakeState.HANDING_OFF
                    || st == WakeCoordinator.WakeState.STT_ACTIVE;
            if (wakeCoordinator != null) wakeCoordinator.stop();
            main.removeCallbacks(releaseKeptScoRunnable);
            stopListening();
            if (handoffInFlight) {
                Log.i(TAG, "stopWakeListening pendant " + st + " — hold SCO conservé");
                main.postDelayed(releaseKeptScoRunnable, KEEP_SCO_TIMEOUT_MS);
            } else {
                releaseWakeServiceScoHold();
            }
            refreshForegroundNotification();
        }

        @Override
        public void pauseWakeListeningKeepSco() {
            // Handoff wake→STT : couper AudioRecord KWS mais garder le lien HFP/SCO
            // sinon le micro casque tombe avant SpeechRecognizer (error 7 / silence).
            // Si encore LISTENING_WAKE (pause avant hit traité), passer en HANDING_OFF
            // pour que wantsListening() soit false et n'auto-relance pas le moteur.
            if (wakeCoordinator != null && wakeCoordinator.wantsListening()) {
                wakeCoordinator.onWakeDetected();
            }
            stopListening();
            main.removeCallbacks(releaseKeptScoRunnable);
            main.postDelayed(releaseKeptScoRunnable, KEEP_SCO_TIMEOUT_MS);
            refreshForegroundNotification();
            try {
                JSONObject f = new JSONObject();
                f.put("wake_service_hold", kwsRouteManager != null
                        && kwsRouteManager.hasWakeServiceScoHold());
                f.put("route", kwsRouteManager != null
                        ? kwsRouteManager.describeRoute() : "");
                f.put("coord_state", wakeCoordinator != null
                        ? wakeCoordinator.getState().state.name() : "");
                PegaseDiagLog.kws(VoiceService.this, "wake_pause_keep_sco", f);
            } catch (Exception ignored) {}
        }

        @Override
        public void setGentleMode(boolean gentle) {
            MediaPlaybackGuard.setGentle(gentle);
        }

        @Override
        public void registerCallback(IWakeWordCallback callback) {
            if (callback != null) callbacks.register(callback);
        }

        @Override
        public void unregisterCallback(IWakeWordCallback callback) {
            if (callback != null) callbacks.unregister(callback);
        }

        @Override
        public void registerHealthCallback(IWakeHealthCallback callback) {
            if (callback != null) healthCallbacks.register(callback);
            if (callback == null) return;
            try {
                callback.onWakeHealthChanged(lastHealth.code);
            } catch (RemoteException ignored) {}
        }

        @Override
        public void unregisterHealthCallback(IWakeHealthCallback callback) {
            if (callback != null) healthCallbacks.unregister(callback);
        }

        @Override
        public int getWakeHealthCode() {
            return currentWakeHealth().code;
        }

        @Override
        public void resetKwsCrashGuard() {
            KwsCrashGuard.resetForUser(VoiceService.this);
            if (wakeCoordinator != null) {
                WakeCoordinator.WakeState st = wakeCoordinator.getState().state;
                if (st == WakeCoordinator.WakeState.COOLDOWN) {
                    wakeCoordinator.stop();
                    wakeCoordinator.start();
                }
            }
            ensureSessionEngines();
            if (wantsListening()) {
                scheduleListen(START_LISTEN_DELAY_MS);
            } else {
                refreshForegroundNotification();
            }
        }

        @Override
        public void notifySttSessionStarted() {
            main.post(() -> {
                if (wakeCoordinator == null) return;
                // Plus de relance KWS tant que STT_ACTIVE (wantsListening=false).
                main.removeCallbacks(listenRunnable);
                stopListening();
                main.removeCallbacks(releaseKeptScoRunnable);
                wakeCoordinator.requestSttSession(ok -> {
                    try {
                        JSONObject f = new JSONObject();
                        f.put("ok", ok);
                        f.put("coord_state", wakeCoordinator.getState().state.name());
                        f.put("rearm_pending", wakeCoordinator.isPostSttRearmPending());
                        f.put("route", kwsRouteManager != null
                                ? kwsRouteManager.describeRoute() : "");
                        PegaseDiagLog.kws(VoiceService.this, "coord_stt_started", f);
                    } catch (Exception ignored) {}
                    refreshForegroundNotification();
                });
            });
        }

        @Override
        public void notifySttSessionEnded() {
            main.post(() -> {
                if (wakeCoordinator == null) return;
                boolean released = wakeCoordinator.releaseSttSession();
                try {
                    JSONObject f = new JSONObject();
                    f.put("released", released);
                    f.put("coord_state", wakeCoordinator.getState().state.name());
                    f.put("rearm_pending", wakeCoordinator.isPostSttRearmPending());
                    PegaseDiagLog.kws(VoiceService.this, "coord_stt_ended", f);
                } catch (Exception ignored) {}
                refreshForegroundNotification();
            });
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        refreshForegroundNotification();
        kwsRouteManager = KwsAudioRouteManager.getInstance(this);
        audioRouteObserver = new AudioRouteObserver(this);
        wakeCoordinator = new WakeCoordinator(this, audioRouteObserver);
        wakeCoordinator.setListener(this::onCoordinatorState);
        audioRouteObserver.setListener(this::onAudioSourceChanged);
        maybeAutoDownloadKws();
    }

    /**
     * Casque HFP branché / débranché pendant l'écoute wake. La décision reste au
     * {@link WakeCoordinator} : ici on ne fait qu'appliquer la conséquence moteur
     * (relance capture sur la nouvelle route) si la session a été re-figée.
     */
    private void onAudioSourceChanged(AudioRouteObserver.AudioSource source) {
        main.post(() -> {
            if (wakeCoordinator == null) return;
            if (!wakeCoordinator.onAudioSourceChanged(source)) return;
            diag("wake_source_refrozen", kwsRouteManager != null
                    ? kwsRouteManager.describeRoute() : "", null);
            if (source == AudioRouteObserver.AudioSource.PHONE_BUILTIN) {
                // Plus de casque : rendre le hold SCO au lieu de le garder pour rien.
                releaseWakeServiceScoHold();
            }
            stopKwsPlanned();
            ensureSessionEngines();
            if (wantsListening()) scheduleListen(400);
        });
    }

    private void onCoordinatorState(WakeCoordinator.Snapshot snap) {
        WakeCoordinator.WakeState prev = lastCoordinatorState;
        lastCoordinatorState = snap.state;
        if (prev == WakeCoordinator.WakeState.COOLDOWN
                && snap.state == WakeCoordinator.WakeState.LISTENING_WAKE) {
            // Fin du COOLDOWN crash-loop : permettre un nouvel essai moteur.
            KwsCrashGuard.reset(this);
        }
        if (snap.state == WakeCoordinator.WakeState.STT_ACTIVE) {
            // Pas de KWS pendant STT / rearm anti-écho.
            main.removeCallbacks(listenRunnable);
            main.post(this::refreshForegroundNotification);
            return;
        }
        if (snap.state == WakeCoordinator.WakeState.LISTENING_WAKE) {
            main.post(() -> {
                ensureSessionEngines();
                if (wantsListening()) scheduleListen(START_LISTEN_DELAY_MS);
            });
        } else {
            main.post(() -> {
                if (snap.state == WakeCoordinator.WakeState.IDLE) {
                    // Fin réelle de session (y compris stop différé appliqué après le STT) :
                    // rendre le hold SCO tout de suite au lieu d'attendre le timeout.
                    main.removeCallbacks(releaseKeptScoRunnable);
                    releaseWakeServiceScoHold();
                }
                refreshForegroundNotification();
            });
        }
    }

    private boolean wantsListening() {
        return wakeCoordinator != null && wakeCoordinator.wantsListening();
    }

    private boolean useOww() {
        return wakeCoordinator != null
                && wakeCoordinator.getSessionBackend() == WakeCoordinator.WakeBackend.OWW;
    }

    private boolean useKws() {
        return wakeCoordinator != null
                && wakeCoordinator.getSessionBackend() == WakeCoordinator.WakeBackend.SHERPA;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        if (wakeCoordinator != null) {
            wakeCoordinator.setListener(null);
            wakeCoordinator.stopNow();
        }
        main.removeCallbacks(releaseKeptScoRunnable);
        stopListening();
        releaseWakeServiceScoHold();
        releaseListenWakeLock();
        destroyRecognizer();
        if (owwEngine != null) {
            owwEngine.release();
            owwEngine = null;
        }
        if (kwsEngine != null) {
            kwsEngine.release();
            kwsEngine = null;
        }
        if (audioRouteObserver != null) {
            audioRouteObserver.release();
            audioRouteObserver = null;
        }
        if (kwsRouteManager != null) {
            kwsRouteManager.release();
            kwsRouteManager = null;
        }
        wakeCoordinator = null;
        callbacks.kill();
        healthCallbacks.kill();
        super.onDestroy();
    }

    private boolean usesLocalWake() {
        return useOww() || useKws();
    }

    /**
     * Charge les moteurs selon le backend figé par {@link WakeCoordinator#start()}.
     * Ne re-sélectionne pas Sherpa/OWW (règle dans le coordinator).
     */
    private void ensureSessionEngines() {
        if (wakeCoordinator == null) return;
        WakeCoordinator.WakeState st = wakeCoordinator.getState().state;
        if (st == WakeCoordinator.WakeState.COOLDOWN) {
            destroyRecognizer();
            refreshForegroundNotification();
            return;
        }
        KwsCrashGuard.bumpConfigGeneration(this, 5);
        if (KwsCrashGuard.shouldDisableKws(this)) {
            Log.e(TAG, "KWS crash-loop — COOLDOWN via WakeCoordinator");
            diag("wake_backend_disabled", "crash_guard", null);
            wakeCoordinator.onCrashGuardTripped();
            stopListening();
            destroyRecognizer();
            refreshForegroundNotification();
            return;
        }
        WakeCoordinator.WakeBackend backend = wakeCoordinator.getSessionBackend();
        if (backend == WakeCoordinator.WakeBackend.OWW) {
            if (owwEngine == null) {
                owwEngine = new OpenWakeWordEngine(this, new OpenWakeWordEngine.Listener() {
                    @Override
                    public void onKeywordDetected(String keyword, float score) {
                        main.post(() -> onWakeDetected(""));
                    }

                    @Override
                    public void onAudioRouteChanged() {
                        onWakeAudioRouteChanged();
                    }
                });
            }
            if (kwsRouteManager != null) {
                owwEngine.setRouteManager(kwsRouteManager);
            }
            if (owwEngine.ensureLoaded()) {
                destroyRecognizer();
                Log.i(TAG, "wake engine = openWakeWord (session backend)");
                refreshForegroundNotification();
                return;
            }
            Log.w(TAG, "OWW session backend but load failed");
        } else if (backend == WakeCoordinator.WakeBackend.SHERPA) {
            if (kwsEngine == null) {
                kwsEngine = new SherpaKwsEngine(this, new SherpaKwsEngine.Listener() {
                    @Override
                    public void onKeywordDetected(String keyword) {
                        onWakeDetected("");
                    }

                    @Override
                    public void onAudioRouteChanged() {
                        onWakeAudioRouteChanged();
                    }
                });
            }
            if (kwsRouteManager != null) {
                kwsEngine.setRouteManager(kwsRouteManager);
            }
            if (kwsEngine.ensureLoaded()) {
                destroyRecognizer();
                Log.i(TAG, "wake engine = Sherpa KWS (session backend)");
                refreshForegroundNotification();
                return;
            }
            Log.w(TAG, "Sherpa session backend but load failed");
        }
        Log.w(TAG, "wake engine = none (backend=" + backend + ")");
        destroyRecognizer();
        refreshForegroundNotification();
    }

    private void maybeAutoDownloadKws() {
        if (KwsModelStore.isModelReady(this) || KwsModelDownloader.isDownloading()) return;
        KwsModelDownloader.download(this, new KwsModelDownloader.Callback() {
            @Override
            public void onProgress(int percent) {}

            @Override
            public void onComplete(boolean success, String message) {
                Log.i(TAG, "KWS download: " + success + " " + message);
                if (!success) return;
                main.post(() -> {
                    boolean wasListening = wantsListening();
                    if (wasListening) {
                        KwsCrashGuard.onPlannedRestart(VoiceService.this);
                    }
                    stopListening();
                    if (wasListening && wakeCoordinator != null) {
                        // Re-fige backend (modèle désormais dispo) puis relance.
                        wakeCoordinator.stop();
                        wakeCoordinator.start();
                        ensureSessionEngines();
                        sttBackoffStep = 0;
                        kwsRestartStreak = 0;
                        scheduleListen(800);
                    } else {
                        ensureSessionEngines();
                    }
                });
            }
        });
    }

    private void initRecognizer() {
        if (usesLocalWake() || recognizer != null) return;
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
                recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this);
            } else if (SpeechRecognizer.isRecognitionAvailable(this)) {
                recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            }
        } catch (Exception e) {
            recognizer = null;
            try {
                if (SpeechRecognizer.isRecognitionAvailable(this)) {
                    recognizer = SpeechRecognizer.createSpeechRecognizer(this);
                }
            } catch (Exception ignored) {
                recognizer = null;
            }
        }
        if (recognizer == null) return;
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onResults(Bundle results) {
                sttRecognizerActive = false;
                handleResults(results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION));
                if (wantsListening()) scheduleListen(nextSttBackoffMs());
            }

            @Override
            public void onError(int error) {
                sttRecognizerActive = false;
                if (!wantsListening()) return;
                scheduleListen(nextSttBackoffMs());
            }

            @Override public void onReadyForSpeech(Bundle params) {}
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() { sttRecognizerActive = false; }
            @Override public void onPartialResults(Bundle partialResults) {
                ArrayList<String> list = partialResults.getStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION);
                if (list == null) return;
                for (String raw : list) {
                    if (WakeWordMatcher.containsWakeWordLite(raw)) {
                        try { recognizer.cancel(); } catch (Exception ignored) {}
                        sttRecognizerActive = false;
                        onWakeDetected(WakeWordMatcher.stripWakePrefixLite(raw));
                        return;
                    }
                }
            }
            @Override public void onEvent(int eventType, Bundle params) {}
        });
    }

    private void destroyRecognizer() {
        if (recognizer != null) {
            try { recognizer.destroy(); } catch (Exception ignored) {}
            recognizer = null;
        }
    }

    private void handleResults(ArrayList<String> list) {
        if (list == null) return;
        for (String raw : list) {
            if (WakeWordMatcher.containsWakeWordLite(raw)) {
                onWakeDetected(WakeWordMatcher.stripWakePrefixLite(raw));
                return;
            }
        }
    }

    /** Casque BT / filaire — relance capture ; backend figé par WakeCoordinator. */
    private void onWakeAudioRouteChanged() {
        if (!wantsListening() || !usesLocalWake()) return;
        String backend = useOww() ? "oww" : "kws";
        Log.i(TAG, backend + " audio route changed — restart capture (backend frozen)");
        diag(backend + "_route_changed", kwsRouteManager != null
                ? kwsRouteManager.describeRoute() : "", null);
        stopKwsPlanned();
        if (wantsListening()) scheduleListen(400);
    }

    /** Arrêt wake local volontaire (redémarrage prévu) — ne pas compter comme crash natif. */
    private void stopKwsPlanned() {
        KwsCrashGuard.onPlannedRestart(this);
        sttRecognizerActive = false;
        if (owwEngine != null) {
            try { owwEngine.stop(); } catch (Exception ignored) {}
        }
        if (kwsEngine != null) {
            try { kwsEngine.stop(); } catch (Exception ignored) {}
        }
        releaseListenWakeLock();
    }

    private void onWakeDetected(String command) {
        long now = System.currentTimeMillis();
        if (now - lastWakeDetectedMs < WAKE_DEBOUNCE_MS) {
            if (wantsListening()) scheduleListen(800);
            return;
        }
        lastWakeDetectedMs = now;
        kwsRestartStreak = 0;
        sttBackoffStep = 0;
        if (wakeCoordinator == null || !wakeCoordinator.onWakeDetected()) {
            Log.d(TAG, "onWakeDetected ignored by coordinator");
            return;
        }
        long transitionId = WakeToSttTrace.begin();
        try {
            JSONObject f = new JSONObject();
            f.put("command", command == null ? "" : command);
            f.put("use_oww", useOww());
            f.put("use_kws", useKws());
            f.put("backend", useOww() ? "oww" : (useKws() ? "sherpa" : "none"));
            f.put("coord_state", wakeCoordinator.getState().state.name());
            WakeToSttTrace.mark(this, "wake_detected", f);
        } catch (Exception ignored) {
            WakeToSttTrace.mark(this, "wake_detected");
        }
        diag("kws_wake_detected", null, command);
        stopListeningForWakeTransition();
        String cmd = command == null ? "" : command.trim();
        notifyWake(cmd, transitionId, WakeToSttTrace.currentT0());
    }

    private void notifyWake(String command, long transitionId, long t0ElapsedMs) {
        int n = callbacks.beginBroadcast();
        Log.i(TAG, "notifyWake callbacks=" + n + " cmd=" + (command == null ? "" : command));
        try {
            for (int i = 0; i < n; i++) {
                try {
                    callbacks.getBroadcastItem(i).onWakeWordDetected(command);
                } catch (RemoteException ignored) {}
            }
        } finally {
            callbacks.finishBroadcast();
        }
        // FGS peut démarrer l'activité même si le launcher n'est pas bindé.
        launchInPlaceVoice(command, transitionId, t0ElapsedMs);
    }

    private void launchInPlaceVoice(String command, long transitionId, long t0ElapsedMs) {
        try {
            Intent i = new Intent(this, com.pegasuscorp.orbe.InPlaceVoiceActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP
                            | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                            | Intent.FLAG_ACTIVITY_NO_ANIMATION
                            | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                    .putExtra("wake_activate", true)
                    .putExtra("wake_command", command == null ? "" : command)
                    .putExtra("wake_speaker_verified", false);
            WakeToSttTrace.attachToIntent(i, transitionId, t0ElapsedMs);
            startActivity(i);
            Log.i(TAG, "InPlaceVoiceActivity started from VoiceService");
        } catch (Exception e) {
            Log.e(TAG, "launchInPlaceVoice failed", e);
            WakeToSttTrace.mark(this, "wake_launch_failed");
        }
    }

    private void scheduleListen(long delayMs) {
        main.removeCallbacks(listenRunnable);
        main.removeCallbacks(forceIdleListenRunnable);
        main.removeCallbacks(kwsMediaPollRunnable);
        main.removeCallbacks(kwsHealthRunnable);
        clearIdleHandler();
        if (!wantsListening()) return;
        main.postDelayed(listenRunnable, delayMs);
    }

    private final Runnable listenRunnable = this::startListenIfReady;
    private final Runnable forceIdleListenRunnable = () -> {
        if (!idleListenPending) return;
        clearIdleHandler();
        if (usesLocalWake()) startKwsListen();
        else startSttListen();
    };
    private final Runnable kwsMediaPollRunnable = this::pollKwsAfterMedia;
    private final Runnable kwsHealthRunnable = this::runKwsHealthCheck;

    private long nextSttBackoffMs() {
        int i = Math.min(sttBackoffStep, STT_BACKOFF_MS.length - 1);
        long delay = STT_BACKOFF_MS[i];
        if (sttBackoffStep < STT_BACKOFF_MS.length - 1) sttBackoffStep++;
        return delay;
    }

    private boolean isLocalWakeRunning() {
        return (useOww() && owwEngine != null && owwEngine.isRunning())
                || (useKws() && kwsEngine != null && kwsEngine.isRunning());
    }

    /** Pendant média : ne pas tear-down KWS/OWW (sinon clignotement + kill OEM). */
    private void pollKwsAfterMedia() {
        if (!wantsListening() || !usesLocalWake()) return;
        if (MediaPlaybackGuard.isOtherAudioPlaying(this)) {
            main.postDelayed(kwsMediaPollRunnable, KWS_MEDIA_POLL_MS);
            return;
        }
        if (isLocalWakeRunning()) {
            main.removeCallbacks(kwsHealthRunnable);
            main.postDelayed(kwsHealthRunnable, KWS_HEALTH_PERIOD_MS);
            return;
        }
        startKwsListen();
    }

    /** Relance wake local si le thread est mort alors qu'on veut encore écouter. */
    private void runKwsHealthCheck() {
        if (!wantsListening() || !usesLocalWake()) return;
        if (isLocalWakeRunning()) {
            kwsRestartStreak = 0;
            if (System.currentTimeMillis() - lastKwsStartMs > 15_000L) {
                KwsCrashGuard.onKwsHealthy(this);
            }
            refreshForegroundNotification();
            main.postDelayed(kwsHealthRunnable, KWS_HEALTH_PERIOD_MS);
            return;
        }
        // Évite le clignotement : ne pas relancer pendant le démarrage du thread
        if (System.currentTimeMillis() - lastKwsStartMs < KWS_HEALTH_GRACE_MS) {
            main.postDelayed(kwsHealthRunnable, KWS_HEALTH_GRACE_MS);
            return;
        }
        if (kwsRestartStreak >= 5 || KwsCrashGuard.shouldDisableKws(this)) {
            Log.w(TAG, "wake dead / crash-guard — COOLDOWN via WakeCoordinator");
            kwsRestartStreak = 0;
            stopKwsPlanned();
            main.removeCallbacks(kwsHealthRunnable);
            if (wakeCoordinator != null) {
                wakeCoordinator.onCrashGuardTripped();
            }
            return;
        }
        kwsRestartStreak++;
        Log.w(TAG, "wake not running while wantsListening — restart #" + kwsRestartStreak);
        stopKwsPlanned();
        scheduleListen(2_500);
    }

    private void clearIdleHandler() {
        idleListenPending = false;
        if (pendingIdleHandler != null) {
            try {
                Looper.myQueue().removeIdleHandler(pendingIdleHandler);
            } catch (Exception ignored) {}
            pendingIdleHandler = null;
        }
    }

    private void startListenIfReady() {
        if (!wantsListening()) return;
        if (!usesLocalWake()
                && (WakeOwwStore.preferCustomWake(this) || KwsModelStore.isModelReady(this))) {
            // Backend figé à start() ; recharger moteurs si absents.
            ensureSessionEngines();
        }
        if (MediaPlaybackGuard.isOtherAudioPlaying(this)) {
            if (usesLocalWake()) {
                if (isLocalWakeRunning()) {
                    main.removeCallbacks(kwsMediaPollRunnable);
                    main.postDelayed(kwsMediaPollRunnable, KWS_MEDIA_POLL_MS);
                    return;
                }
                main.removeCallbacks(kwsMediaPollRunnable);
                main.postDelayed(kwsMediaPollRunnable, KWS_MEDIA_POLL_MS);
                return;
            }
            scheduleListen(MEDIA_PAUSE_POLL_MS);
            return;
        }
        if (usesLocalWake()) {
            startKwsListen();
            return;
        }
        // Sans wake local : ne pas lancer SpeechRecognizer en boucle (micro tué toutes les ~10 s).
        Log.w(TAG, "listen skipped — wake local off, no STT background");
    }

    private void startKwsListen() {
        if (!wantsListening() || !usesLocalWake()) return;
        if ((useOww() && owwEngine == null) || (useKws() && kwsEngine == null)) {
            ensureSessionEngines();
        }
        if (useOww()) {
            if (owwEngine == null || !owwEngine.isReady()) {
                Log.w(TAG, "OWW not ready — retry in 5s");
                diag("oww_not_ready", null, null);
                refreshForegroundNotification();
                scheduleListen(5_000);
                return;
            }
        } else if (kwsEngine == null || !kwsEngine.isReady()) {
            Log.w(TAG, "KWS not ready — retry in 5s (model=" + KwsModelStore.isModelReady(this)
                    + " crashGuard=" + KwsCrashGuard.shouldDisableKws(this) + ")");
            diag("kws_not_ready", null, null);
            refreshForegroundNotification();
            scheduleListen(5_000);
            return;
        }
        if (MediaPlaybackGuard.isOtherAudioPlaying(this)) {
            scheduleListen(KWS_MEDIA_POLL_MS);
            return;
        }
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            refreshForegroundNotification();
            return;
        }
        if (isLocalWakeRunning()) return;
        if (wakeScoAcquireInFlight) return;
        // Respecter la source figée par WakeCoordinator : pas de danse SCO si session téléphone.
        boolean sessionPhone = wakeCoordinator != null
                && wakeCoordinator.getState().source
                == AudioRouteObserver.AudioSource.PHONE_BUILTIN;
        if (sessionPhone) {
            if (kwsRouteManager != null) {
                kwsRouteManager.forcePhoneBuiltin();
            }
            startKwsListenEngine();
            return;
        }
        // SCO une seule fois pour la durée d'écoute wake (pas à chaque openMic / cycle).
        if (kwsRouteManager != null) {
            wakeScoAcquireInFlight = true;
            kwsRouteManager.ensureWakeServiceScoHoldAsync(ok -> {
                wakeScoAcquireInFlight = false;
                if (!wantsListening() || !usesLocalWake()) return;
                if (!ok && kwsRouteManager.wantsBluetoothMic()) {
                    Log.w(TAG, "SCO wake hold failed — retry in 1.5s");
                    wakeScoAcquireInFlight = true;
                    main.postDelayed(() -> {
                        if (!wantsListening() || !usesLocalWake()) {
                            wakeScoAcquireInFlight = false;
                            return;
                        }
                        kwsRouteManager.ensureWakeServiceScoHoldAsync(ok2 -> {
                            wakeScoAcquireInFlight = false;
                            if (!wantsListening() || !usesLocalWake()) return;
                            if (!ok2 && kwsRouteManager.wantsBluetoothMic()) {
                                // HFP absent (A2DP-only / casque sans profil téléphone) :
                                // bascule téléphone pour ne pas rester sourd.
                                // Sinon (proxy/SCO flappy) : réessaie sans micro phone.
                                String fail = kwsRouteManager.lastScoFailReason();
                                boolean noHfp = fail != null && (fail.contains("no_hfp")
                                        || fail.contains("a2dp_only"));
                                if (noHfp) {
                                    Log.w(TAG, "SCO sans HFP — fallback micro téléphone");
                                    try {
                                        JSONObject f = new JSONObject();
                                        f.put("fail_reason", fail);
                                        f.put("route", kwsRouteManager.describeRoute());
                                        PegaseDiagLog.kws(VoiceService.this,
                                                "sco_phone_fallback_no_hfp", f);
                                    } catch (Exception ignored) {}
                                    // Le coordinator arbitre la dégradation : sinon il reste
                                    // sur BLUETOOTH_HFP et le STT suivant retente un SCO
                                    // condamné (~15 s de silence puis error 7).
                                    if (wakeCoordinator != null) {
                                        wakeCoordinator.notifyScoUnavailable();
                                    }
                                    kwsRouteManager.forcePhoneBuiltin();
                                    // Backend re-sélectionné avec la nouvelle source.
                                    ensureSessionEngines();
                                    startKwsListenEngine();
                                    return;
                                }
                                Log.w(TAG, "SCO wake hold retry failed — reschedule, pas de fallback phone");
                                try {
                                    JSONObject f = new JSONObject();
                                    f.put("route", kwsRouteManager.describeRoute());
                                    PegaseDiagLog.kws(VoiceService.this,
                                            "sco_bt_no_phone_fallback", f);
                                } catch (Exception ignored) {}
                                scheduleListen(12_000);
                                return;
                            }
                            startKwsListenEngine();
                        });
                    }, KwsAudioRouteManager.WAKE_SCO_RETRY_DELAY_MS);
                    return;
                }
                startKwsListenEngine();
            });
            return;
        }
        startKwsListenEngine();
    }

    private void startKwsListenEngine() {
        if (!wantsListening() || !usesLocalWake()) return;
        if (isLocalWakeRunning()) return;
        // Le backend a pu être re-sélectionné (changement de source / SCO indisponible) :
        // sans moteur chargé on replanifie au lieu de déréférencer null.
        if ((useOww() && owwEngine == null) || (useKws() && kwsEngine == null)) {
            Log.w(TAG, "moteur absent pour backend courant — recharge puis retry");
            ensureSessionEngines();
            if (wantsListening()) scheduleListen(400);
            return;
        }
        acquireListenWakeLock();
        lastKwsStartMs = System.currentTimeMillis();
        if (useOww()) {
            diag("oww_listen_start", kwsRouteManager != null
                    ? kwsRouteManager.describeRoute() : "", null);
            owwEngine.start();
        } else {
            diag("kws_listen_start", kwsRouteManager != null
                    ? kwsRouteManager.describeRoute() : "", null);
            kwsEngine.start();
        }
        main.removeCallbacks(kwsHealthRunnable);
        main.postDelayed(kwsHealthRunnable, KWS_HEALTH_FIRST_MS);
        refreshForegroundNotification();
    }

    /** Coupe le hold SCO wake (stop écoute / destroy) — pas entre deux wakes. */
    private void releaseWakeServiceScoHold() {
        wakeScoAcquireInFlight = false;
        if (kwsRouteManager != null) {
            kwsRouteManager.releaseWakeServiceScoHold();
        }
    }

    private void startSttListen() {
        if (!wantsListening() || usesLocalWake()) return;
        if (recognizer == null) initRecognizer();
        if (recognizer == null || sttRecognizerActive) return;
        if (MediaPlaybackGuard.isOtherAudioPlaying(this)) {
            scheduleListen(MEDIA_PAUSE_POLL_MS);
            return;
        }
        if (!MediaPlaybackGuard.canStartSttSession()) {
            scheduleListen(nextSttBackoffMs());
            return;
        }
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                .putExtra("android.speech.extra.SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLISECONDS", 8000)
                .putExtra("android.speech.extra.SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLISECONDS", 6000)
                .putExtra("android.speech.extra.SPEECH_INPUT_MINIMUM_LENGTH_MILLISECONDS", 700);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        }
        try {
            recognizer.startListening(intent);
            MediaPlaybackGuard.markSttSessionStarted();
            sttRecognizerActive = true;
            acquireListenWakeLock();
        } catch (RuntimeException ignored) {
            sttRecognizerActive = false;
            scheduleListen(nextSttBackoffMs());
        }
    }

    private void stopListening() {
        sttRecognizerActive = false;
        diag("kws_listen_stop", null, null);
        clearIdleHandler();
        main.removeCallbacks(listenRunnable);
        main.removeCallbacks(forceIdleListenRunnable);
        main.removeCallbacks(kwsMediaPollRunnable);
        main.removeCallbacks(kwsHealthRunnable);
        if (owwEngine != null) {
            try { owwEngine.stop(); } catch (Exception ignored) {}
        }
        if (kwsEngine != null) {
            try { kwsEngine.stop(); } catch (Exception ignored) {}
        }
        if (recognizer != null) {
            try { recognizer.cancel(); } catch (Exception ignored) {}
        }
        releaseListenWakeLock();
        refreshForegroundNotification();
    }

    /**
     * Arrêt wake après HIT — trace {@code kws_release_*} pour mesurer join/closeMic
     * avant l'ouverture STT côté launcher.
     */
    private void stopListeningForWakeTransition() {
        sttRecognizerActive = false;
        diag("kws_listen_stop", null, null);
        clearIdleHandler();
        main.removeCallbacks(listenRunnable);
        main.removeCallbacks(forceIdleListenRunnable);
        main.removeCallbacks(kwsMediaPollRunnable);
        main.removeCallbacks(kwsHealthRunnable);
        String backend = useOww() ? "oww" : (useKws() ? "sherpa" : "none");
        try {
            JSONObject start = new JSONObject();
            start.put("backend", backend);
            start.put("oww_running", owwEngine != null && owwEngine.isRunning());
            start.put("kws_running", kwsEngine != null && kwsEngine.isRunning());
            WakeToSttTrace.mark(this, "kws_release_start", start);
        } catch (Exception ignored) {
            WakeToSttTrace.mark(this, "kws_release_start");
        }
        long tStop = android.os.SystemClock.elapsedRealtime();
        if (owwEngine != null) {
            try { owwEngine.stop(); } catch (Exception ignored) {}
        }
        if (kwsEngine != null) {
            try { kwsEngine.stop(); } catch (Exception ignored) {}
        }
        long joinMs = android.os.SystemClock.elapsedRealtime() - tStop;
        if (recognizer != null) {
            try { recognizer.cancel(); } catch (Exception ignored) {}
        }
        releaseListenWakeLock();
        refreshForegroundNotification();
        try {
            JSONObject done = new JSONObject();
            done.put("backend", backend);
            done.put("join_ms", joinMs);
            done.put("oww_running", owwEngine != null && owwEngine.isRunning());
            done.put("kws_running", kwsEngine != null && kwsEngine.isRunning());
            WakeToSttTrace.mark(this, "kws_release_done", done);
        } catch (Exception ignored) {
            WakeToSttTrace.mark(this, "kws_release_done");
        }
    }

    private WakeHealthStatus currentWakeHealth() {
        boolean modelReady = WakeOwwStore.isModelReady(this) || KwsModelStore.isModelReady(this);
        boolean crashCooldown = wakeCoordinator != null
                && wakeCoordinator.getState().state == WakeCoordinator.WakeState.COOLDOWN;
        return WakeHealthEvaluator.evaluate(
                wantsListening(),
                KwsCrashGuard.shouldDisableKws(this) || crashCooldown,
                isLocalWakeRunning(),
                modelReady);
    }

    private void refreshForegroundNotification() {
        WakeHealthStatus health = currentWakeHealth();
        if (health != lastHealth) {
            lastHealth = health;
            dispatchHealthChanged(health);
        }
        final String title = getString(com.pegasuscorp.orbe.R.string.wake_notif_title);
        final String text = notificationTextFor(health);
        String fingerprint = health.name() + '\n' + text;
        if (fingerprint.equals(lastNotifFingerprint) && foregroundStarted) return;

        Notification n = buildNotification(title, text);
        if (!foregroundStarted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(NOTIF_ID, n);
            }
            foregroundStarted = true;
        } else {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.notify(NOTIF_ID, n);
        }
        lastNotifFingerprint = fingerprint;
    }

    private String notificationTextFor(WakeHealthStatus health) {
        switch (health) {
            case LISTENING:
                if (!KwsModelStore.isModelReady(this)) {
                    return getString(com.pegasuscorp.orbe.R.string.wake_notif_model_missing);
                }
                return getString(com.pegasuscorp.orbe.R.string.wake_notif_listening);
            case PROBLEM:
                if (KwsCrashGuard.shouldDisableKws(this)) {
                    return getString(com.pegasuscorp.orbe.R.string.wake_notif_crash_guard);
                }
                if (!KwsModelStore.isModelReady(this)) {
                    return getString(com.pegasuscorp.orbe.R.string.wake_notif_model_missing);
                }
                return getString(com.pegasuscorp.orbe.R.string.wake_notif_problem);
            case OFF:
            default:
                return getString(com.pegasuscorp.orbe.R.string.wake_notif_idle);
        }
    }

    private void dispatchHealthChanged(WakeHealthStatus health) {
        int n = healthCallbacks.beginBroadcast();
        try {
            for (int i = 0; i < n; i++) {
                try {
                    healthCallbacks.getBroadcastItem(i).onWakeHealthChanged(health.code);
                } catch (RemoteException ignored) {}
            }
        } finally {
            healthCallbacks.finishBroadcast();
        }
    }

    private void acquireListenWakeLock() {
        try {
            if (listenWakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                if (pm == null) return;
                listenWakeLock = pm.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK, "pegase:voice_listen");
                listenWakeLock.setReferenceCounted(false);
            }
            if (!listenWakeLock.isHeld()) {
                listenWakeLock.acquire();
            }
        } catch (Exception e) {
            Log.w(TAG, "wake lock acquire", e);
        }
    }

    private void releaseListenWakeLock() {
        PowerManager.WakeLock wl = listenWakeLock;
        if (wl == null) return;
        try {
            if (wl.isHeld()) wl.release();
        } catch (Exception e) {
            Log.w(TAG, "wake lock release", e);
        }
    }

    private Notification buildNotification(String title, String text) {
        Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (open == null) {
            open = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .setPackage(getPackageName())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        } else {
            open = new Intent(open).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        }
        PendingIntent tap = PendingIntent.getActivity(this, 2, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(com.pegasuscorp.orbe.R.drawable.ic_stat_pegase)
                .setContentIntent(tap)
                .setOngoing(true)
                .setSilent(true)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Pégase micro (discret)", NotificationManager.IMPORTANCE_MIN);
            ch.setDescription("Requis par Android pour le service micro — masqué de la barre");
            ch.setSound(null, null);
            ch.enableVibration(false);
            ch.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private void diag(String event, String route, String command) {
        try {
            JSONObject f = new JSONObject();
            f.put("want_listening", wantsListening());
            f.put("use_kws", useKws());
            f.put("use_oww", useOww());
            f.put("listening", isLocalWakeRunning() || sttRecognizerActive);
            if (wakeCoordinator != null) {
                f.put("coord_state", wakeCoordinator.getState().state.name());
                f.put("backend", wakeCoordinator.getSessionBackend().name());
            }
            if (route != null) f.put("route", route);
            if (command != null) f.put("command", command);
            PegaseDiagLog.kws(this, event, f);
        } catch (Exception ignored) {}
    }

    public static Intent bindIntent(Context ctx) {
        return new Intent(ctx, VoiceService.class).setAction(ACTION_BIND);
    }

    public static Intent startIntent(Context ctx) {
        return new Intent(ctx, VoiceService.class);
    }
}
