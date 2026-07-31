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

import java.util.ArrayList;

/**
 * Wake word uniquement — processus {@code :voice}.
 * <p>
 * Préfère Sherpa KWS local si le modèle est installé ; sinon boucle
 * {@link SpeechRecognizer} (fallback). Start/stop via binder — pas de
 * {@link PegaseWakeController} / prefs multi-process.
 */
public class VoiceService extends Service {

    public static final String ACTION_BIND = "com.pegasuscorp.orbe.voice.BIND";

    private static final String TAG = "VoiceService";
    private static final String CHANNEL_ID = "pegase_voice_wake_min";
    private static final int NOTIF_ID = 78;
    private static final long MEDIA_PAUSE_POLL_MS = 8_000L;
    private static final long START_LISTEN_DELAY_MS = 800L;
    private static final long WAKE_DEBOUNCE_MS = 1800L;
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
    private boolean useKws;

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
        kwsRouteManager = new KwsAudioRouteManager(this);
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

    private void refreshWakeBackend() {
        useKws = false;
        // Nouvelle config KWS : laisser une chance après les crashs zipformer.
        KwsCrashGuard.bumpConfigGeneration(this, 5);
        if (KwsCrashGuard.shouldDisableKws(this)) {
            Log.e(TAG, "KWS disabled (crash loop) — pas de STT duty-cycle (évite kill micro)");
            destroyRecognizer();
            refreshForegroundNotification();
            return;
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
                        onKwsAudioRouteChanged();
                    }
                });
                if (kwsRouteManager != null) {
                    kwsEngine.setRouteManager(kwsRouteManager);
                }
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
        Log.w(TAG, "wake backend = none (KWS indisponible, STT désactivé en arrière-plan)");
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
        if (useKws || recognizer != null) return;
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

    /** Casque BT branché/débranché après démarrage du KWS — relance capture sur la nouvelle route. */
    private void onKwsAudioRouteChanged() {
        if (!wantListening || !useKws) return;
        Log.i(TAG, "KWS audio route changed — restarting capture");
        KwsCrashGuard.onPlannedRestart(this);
        listening = false;
        if (kwsEngine != null) {
            try {
                kwsEngine.stop();
            } catch (Exception ignored) {}
        }
        releaseListenWakeLock();
        scheduleListen(400);
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
        stopListening();
        String cmd = command == null ? "" : command.trim();
        notifyWake(cmd);
    }

    private void notifyWake(String command) {
        int n = callbacks.beginBroadcast();
        try {
            for (int i = 0; i < n; i++) {
                try {
                    callbacks.getBroadcastItem(i).onWakeWordDetected(command);
                } catch (RemoteException ignored) {}
            }
        } finally {
            callbacks.finishBroadcast();
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
        if (useKws) startKwsListen();
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

    /** Pendant média : ne pas tear-down KWS (sinon clignotement + kill OEM). */
    private void pollKwsAfterMedia() {
        if (!wantListening || !useKws) return;
        if (MediaPlaybackGuard.isOtherAudioPlaying(this)) {
            main.postDelayed(kwsMediaPollRunnable, KWS_MEDIA_POLL_MS);
            return;
        }
        if (kwsEngine != null && kwsEngine.isRunning()) {
            main.removeCallbacks(kwsHealthRunnable);
            main.postDelayed(kwsHealthRunnable, KWS_HEALTH_PERIOD_MS);
            return;
        }
        startKwsListen();
    }

    /** Relance KWS si le thread est mort alors qu'on veut encore écouter. */
    private void runKwsHealthCheck() {
        if (!wantListening || !useKws) return;
        if (kwsEngine != null && kwsEngine.isRunning()) {
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
            Log.w(TAG, "KWS dead ×5 — pause " + (KWS_RETRY_AFTER_FAIL_MS / 1000)
                    + "s puis nouvel essai KWS (pas de STT)");
            kwsRestartStreak = 0;
            listening = false;
            if (kwsEngine != null) {
                try { kwsEngine.stop(); } catch (Exception ignored) {}
            }
            releaseListenWakeLock();
            main.removeCallbacks(kwsHealthRunnable);
            main.postDelayed(() -> {
                if (wantListening && useKws) scheduleListen(0);
            }, KWS_RETRY_AFTER_FAIL_MS);
            return;
        }
        kwsRestartStreak++;
        Log.w(TAG, "KWS not running while wantListening — restart #" + kwsRestartStreak);
        KwsCrashGuard.onPlannedRestart(this);
        listening = false;
        if (kwsEngine != null) {
            try { kwsEngine.stop(); } catch (Exception ignored) {}
        }
        releaseListenWakeLock();
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
        if (!useKws && KwsModelStore.isModelReady(this)) {
            refreshWakeBackend();
        }
        if (MediaPlaybackGuard.isOtherAudioPlaying(this)) {
            if (useKws) {
                // KWS saute les frames média en interne — ne pas stop/start
                if (kwsEngine != null && kwsEngine.isRunning()) {
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
        if (useKws) {
            startKwsListen();
            return;
        }
        // Sans KWS : ne pas lancer SpeechRecognizer en boucle (micro tué toutes les ~10 s).
        Log.w(TAG, "listen skipped — KWS off, no STT background");
    }

    private void startKwsListen() {
        if (!wantListening || !useKws) return;
        if (kwsEngine == null) refreshWakeBackend();
        if (kwsEngine == null || !kwsEngine.isReady()) {
            Log.w(TAG, "KWS not ready — retry in 5s (model=" + KwsModelStore.isModelReady(this)
                    + " crashGuard=" + KwsCrashGuard.shouldDisableKws(this) + ")");
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
        if (kwsEngine.isRunning()) return;
        listening = true;
        acquireListenWakeLock();
        lastKwsStartMs = System.currentTimeMillis();
        kwsEngine.start();
        main.removeCallbacks(kwsHealthRunnable);
        main.postDelayed(kwsHealthRunnable, KWS_HEALTH_FIRST_MS);
        refreshForegroundNotification();
    }

    private void startSttListen() {
        if (!wantListening || useKws) return;
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
        clearIdleHandler();
        main.removeCallbacks(listenRunnable);
        main.removeCallbacks(forceIdleListenRunnable);
        main.removeCallbacks(kwsMediaPollRunnable);
        main.removeCallbacks(kwsHealthRunnable);
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
        boolean running = useKws && kwsEngine != null && kwsEngine.isRunning();
        return WakeHealthEvaluator.evaluate(
                wantListening,
                KwsCrashGuard.shouldDisableKws(this),
                running,
                KwsModelStore.isModelReady(this));
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
                return getString(com.pegasuscorp.orbe.R.string.wake_notif_listening);
            case PROBLEM:
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

    public static Intent bindIntent(Context ctx) {
        return new Intent(ctx, VoiceService.class).setAction(ACTION_BIND);
    }

    public static Intent startIntent(Context ctx) {
        return new Intent(ctx, VoiceService.class);
    }
}
