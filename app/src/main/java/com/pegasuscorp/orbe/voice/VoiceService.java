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
    /** Si le thread KWS meurt sans mot-clé, relancer tant que wantListening. */
    private static final long KWS_HEALTH_FIRST_MS = 4_000L;
    private static final long KWS_HEALTH_PERIOD_MS = 30_000L;
    private static final long KWS_HEALTH_GRACE_MS = 3_500L;
    private static final long KWS_RETRY_AFTER_FAIL_MS = 45_000L;
    /** STT en arrière-plan = ouvre/ferme le micro → OEM tue le service. Backoff fort. */
    private static final long[] STT_BACKOFF_MS = { 20_000L, 45_000L, 90_000L };

    private final Handler main = new Handler(Looper.getMainLooper());
    private final RemoteCallbackList<IWakeWordCallback> callbacks = new RemoteCallbackList<>();
    private final RemoteCallbackList<IWakeHealthCallback> healthCallbacks =
            new RemoteCallbackList<>();

    private SpeechRecognizer recognizer;
    private KwsAudioRouteManager kwsRouteManager;
    private SherpaKwsEngine kwsEngine;
    private OpenWakeWordEngine owwEngine;
    /** Sherpa zipformer (filet). */
    private boolean useKws;
    /** openWakeWord custom (prioritaire). */
    private boolean useOww;

    private boolean listening;
    /** true = le launcher a demandé l'écoute. */
    private boolean wantListening;
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

    private final IVoiceWakeService.Stub binder = new IVoiceWakeService.Stub() {
        @Override
        public void startWakeListening() {
            wantListening = true;
            sttBackoffStep = 0;
            kwsRestartStreak = 0;
            refreshForegroundNotification();
            scheduleListen(START_LISTEN_DELAY_MS);
        }

        @Override
        public void stopWakeListening() {
            wantListening = false;
            stopListening();
            refreshForegroundNotification();
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
            refreshWakeBackend();
            if (wantListening) {
                scheduleListen(START_LISTEN_DELAY_MS);
            } else {
                refreshForegroundNotification();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        refreshForegroundNotification();
        kwsRouteManager = KwsAudioRouteManager.getInstance(this);
        refreshWakeBackend();
        maybeAutoDownloadKws();
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
        wantListening = false;
        stopListening();
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
        if (kwsRouteManager != null) {
            kwsRouteManager.release();
            kwsRouteManager = null;
        }
        callbacks.kill();
        healthCallbacks.kill();
        super.onDestroy();
    }

    private boolean usesLocalWake() {
        return useOww || useKws;
    }

    private void refreshWakeBackend() {
        useKws = false;
        useOww = false;
        // Nouvelle config KWS : laisser une chance après les crashs zipformer.
        KwsCrashGuard.bumpConfigGeneration(this, 5);
        if (KwsCrashGuard.shouldDisableKws(this)) {
            Log.e(TAG, "KWS disabled (crash loop) — pas de STT duty-cycle (évite kill micro)");
            diag("wake_backend_disabled", "crash_guard", null);
            destroyRecognizer();
            refreshForegroundNotification();
            return;
        }
        // Production : Sherpa par défaut. openWakeWord uniquement si opt-in explicite.
        boolean preferOww = WakeOwwStore.preferCustomWake(this) && WakeOwwStore.isModelReady(this);
        if (preferOww) {
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
                useOww = true;
                destroyRecognizer();
                Log.i(TAG, "wake backend = openWakeWord (prefer_custom)");
                refreshForegroundNotification();
                return;
            }
            Log.w(TAG, "OWW preferé mais load échoué — fallback Sherpa");
        }
        if (KwsModelStore.isModelReady(this)) {
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
                useKws = true;
                destroyRecognizer();
                Log.i(TAG, "wake backend = Sherpa KWS");
                refreshForegroundNotification();
                return;
            }
        }
        // Pas de fallback STT en boucle : ouvre/ferme le micro → OEM kill + point vert.
        Log.w(TAG, "wake backend = none (OWW/KWS indisponible, STT désactivé en arrière-plan)");
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
                    boolean wasListening = wantListening;
                    if (wasListening) {
                        KwsCrashGuard.onPlannedRestart(VoiceService.this);
                    }
                    stopListening();
                    refreshWakeBackend();
                    // Pas de nm.notify — évite le spam / clignotement FGS
                    if (wasListening) {
                        wantListening = true;
                        sttBackoffStep = 0;
                        kwsRestartStreak = 0;
                        scheduleListen(800);
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
                listening = false;
                handleResults(results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION));
                if (wantListening) scheduleListen(nextSttBackoffMs());
            }

            @Override
            public void onError(int error) {
                listening = false;
                if (!wantListening) return;
                scheduleListen(nextSttBackoffMs());
            }

            @Override public void onReadyForSpeech(Bundle params) {}
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() { listening = false; }
            @Override public void onPartialResults(Bundle partialResults) {
                ArrayList<String> list = partialResults.getStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION);
                if (list == null) return;
                for (String raw : list) {
                    if (WakeWordMatcher.containsWakeWordLite(raw)) {
                        try { recognizer.cancel(); } catch (Exception ignored) {}
                        listening = false;
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

    /** Casque BT / filaire — relance capture OWW ou Sherpa sur la nouvelle route. */
    private void onWakeAudioRouteChanged() {
        if (!wantListening || !usesLocalWake()) return;
        String backend = useOww ? "oww" : "kws";
        Log.i(TAG, backend + " audio route changed — restarting capture");
        diag(backend + "_route_changed", kwsRouteManager != null
                ? kwsRouteManager.describeRoute() : "", null);
        stopKwsPlanned();
        scheduleListen(400);
    }

    /** Arrêt wake local volontaire (redémarrage prévu) — ne pas compter comme crash natif. */
    private void stopKwsPlanned() {
        KwsCrashGuard.onPlannedRestart(this);
        listening = false;
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
            if (wantListening) scheduleListen(800);
            return;
        }
        lastWakeDetectedMs = now;
        kwsRestartStreak = 0;
        sttBackoffStep = 0;
        wantListening = false;
        diag("kws_wake_detected", null, command);
        stopListening();
        String cmd = command == null ? "" : command.trim();
        notifyWake(cmd);
    }

    private void notifyWake(String command) {
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
        launchInPlaceVoice(command);
    }

    private void launchInPlaceVoice(String command) {
        try {
            Intent i = new Intent(this, com.pegasuscorp.orbe.InPlaceVoiceActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_NO_ANIMATION
                            | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                    .putExtra("wake_activate", true)
                    .putExtra("wake_command", command == null ? "" : command)
                    .putExtra("wake_speaker_verified", false);
            startActivity(i);
            Log.i(TAG, "InPlaceVoiceActivity started from VoiceService");
        } catch (Exception e) {
            Log.e(TAG, "launchInPlaceVoice failed", e);
        }
    }

    private void scheduleListen(long delayMs) {
        main.removeCallbacks(listenRunnable);
        main.removeCallbacks(forceIdleListenRunnable);
        main.removeCallbacks(kwsMediaPollRunnable);
        main.removeCallbacks(kwsHealthRunnable);
        clearIdleHandler();
        if (!wantListening) return;
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
        return (useOww && owwEngine != null && owwEngine.isRunning())
                || (useKws && kwsEngine != null && kwsEngine.isRunning());
    }

    /** Pendant média : ne pas tear-down KWS/OWW (sinon clignotement + kill OEM). */
    private void pollKwsAfterMedia() {
        if (!wantListening || !usesLocalWake()) return;
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
        if (!wantListening || !usesLocalWake()) return;
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
        if (kwsRestartStreak >= 5) {
            // Ne PAS basculer sur STT en boucle — ça spam le micro et Android tue le service.
            Log.w(TAG, "wake dead ×5 — pause " + (KWS_RETRY_AFTER_FAIL_MS / 1000)
                    + "s puis nouvel essai (pas de STT)");
            kwsRestartStreak = 0;
            stopKwsPlanned();
            main.removeCallbacks(kwsHealthRunnable);
            main.postDelayed(() -> {
                if (wantListening && usesLocalWake()) scheduleListen(0);
            }, KWS_RETRY_AFTER_FAIL_MS);
            return;
        }
        kwsRestartStreak++;
        Log.w(TAG, "wake not running while wantListening — restart #" + kwsRestartStreak);
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
        if (!wantListening) return;
        // Bascule OWW seulement si opt-in ; sinon rester / revenir sur Sherpa.
        boolean preferOww = WakeOwwStore.preferCustomWake(this) && WakeOwwStore.isModelReady(this);
        if (preferOww && !useOww) {
            Log.i(TAG, "OWW preferred — switching backend from "
                    + (useKws ? "Sherpa" : "none"));
            stopKwsPlanned();
            refreshWakeBackend();
        } else if (useOww && !preferOww) {
            Log.i(TAG, "OWW not preferred — switching back to Sherpa default");
            stopKwsPlanned();
            refreshWakeBackend();
        } else if (!usesLocalWake()
                && (preferOww || KwsModelStore.isModelReady(this))) {
            refreshWakeBackend();
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
        if (!wantListening || !usesLocalWake()) return;
        if ((useOww && owwEngine == null) || (useKws && kwsEngine == null)) {
            refreshWakeBackend();
        }
        if (useOww) {
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
        listening = true;
        acquireListenWakeLock();
        lastKwsStartMs = System.currentTimeMillis();
        if (useOww) {
            diag("oww_listen_start", null, null);
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

    private void startSttListen() {
        if (!wantListening || usesLocalWake()) return;
        if (recognizer == null) initRecognizer();
        if (recognizer == null || listening) return;
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
            listening = true;
            acquireListenWakeLock();
        } catch (RuntimeException ignored) {
            listening = false;
            scheduleListen(nextSttBackoffMs());
        }
    }

    private void stopListening() {
        listening = false;
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

    private WakeHealthStatus currentWakeHealth() {
        boolean modelReady = WakeOwwStore.isModelReady(this) || KwsModelStore.isModelReady(this);
        return WakeHealthEvaluator.evaluate(
                wantListening,
                KwsCrashGuard.shouldDisableKws(this),
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
            f.put("want_listening", wantListening);
            f.put("use_kws", useKws);
            f.put("use_oww", useOww);
            f.put("listening", listening);
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
