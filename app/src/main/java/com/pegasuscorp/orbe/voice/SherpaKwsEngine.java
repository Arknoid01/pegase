package com.pegasuscorp.orbe.voice;

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.KeywordSpotter;
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig;
import com.k2fsa.sherpa.onnx.KeywordSpotterResult;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;

import java.io.File;

/**
 * Keyword spotting local (Sherpa) — micro 16 kHz, process {@code :voice} uniquement.
 * Route audio (Bluetooth SCO, casque filaire, micro téléphone) via {@link KwsAudioRouteManager}.
 */
public final class SherpaKwsEngine {

    public interface Listener {
        /** Mot détecté (souvent tokenisé) — la commande suit côté conversation STT. */
        void onKeywordDetected(String keyword);

        /** Casque BT / filaire branché ou débranché pendant l'écoute KWS. */
        default void onAudioRouteChanged() {}
    }

    private static final String TAG = "SherpaKws";
    private static final int SAMPLE_RATE = 16_000;
    private static final float INTERVAL_SEC = 0.1f;
    /** Seuil Sherpa global (les lignes keywords.txt peuvent overrider). */
    private static final float KEYWORDS_THRESHOLD = 0.04f;
    private static final float KEYWORDS_SCORE = 5.0f;
    /**
     * Rejette un HIT trop faible (bruit poche / frottement).
     * Utilise le pic RMS sur ~1,5 s (pas le frame de fin d'énoncé, souvent trop bas).
     * Vrai PEGASE rejeté à −61 sur frame fin alors que la parole était ~−25…−38.
     */
    private static final float MIN_HIT_RMS_DB = -55f;
    /** Fenêtre pic RMS : 15 × 100 ms. */
    private static final int PEAK_RMS_WINDOW = 15;
    /** ~2 s entre deux logs probe (100 ms × 20). */
    private static final int PROBE_EVERY_READS = 20;
    /**
     * Après parole, recréer le OnlineStream (pas reset mid-mot).
     * Évite les HIT retardés type HEY_PEGASE collés sur une conversation.
     * 1,5 s ≫ trailing blanks du spotter (~200–400 ms).
     */
    private static final int SILENCE_RECREATE_FRAMES = 15;
    private static final float SPEECH_RMS_DB = -45f;
    private static final float QUIET_RMS_DB = -58f;
    /** Composés (Hey/Ok…) : exigent un pic plus fort que les alias courts. */
    private static final float MIN_COMPOUND_HIT_RMS_DB = -42f;

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;

    private KwsAudioRouteManager routeManager;
    private KeywordSpotter kws;
    private OnlineStream stream;
    private AudioRecord audioRecord;
    private Thread thread;
    private volatile boolean wantRun;
    private volatile boolean running;
    private volatile boolean nativeBroken;
    private volatile boolean routeChanged;
    /** prepareCapture / releaseCapture ne doivent s'exécuter que depuis le thread KWS. */
    private final Object captureLock = new Object();
    private int readCount;

    public SherpaKwsEngine(Context context, Listener listener) {
        this.app = context.getApplicationContext();
        this.listener = listener;
    }

    /** À appeler avant {@link #start()} (typiquement depuis {@code VoiceService.onCreate}). */
    public void setRouteManager(KwsAudioRouteManager routeManager) {
        this.routeManager = routeManager;
        if (routeManager != null) {
            routeManager.setRouteChangeListener(this::onExternalRouteChange);
        }
    }

    public boolean isReady() {
        return !nativeBroken && KwsModelStore.isModelReady(app) && ensureLoaded();
    }

    public boolean isNativeBroken() {
        return nativeBroken;
    }

    public boolean isRunning() {
        return running;
    }

    /** Charge le modèle si présent. */
    public synchronized boolean ensureLoaded() {
        if (nativeBroken) return false;
        if (kws != null) return true;
        KwsModelStore.ensureKeywords(app);
        KwsModelStore.logModelIdentity(app);
        if (!KwsModelStore.isModelReady(app)) {
            Log.w(TAG, "init skipped — model files not ready");
            return false;
        }
        File enc = KwsModelStore.encoderFile(app);
        File dec = KwsModelStore.decoderFile(app);
        File join = KwsModelStore.joinerFile(app);
        File tok = KwsModelStore.tokensFile(app);
        File kw = KwsModelStore.keywordsFile(app);
        try {
            OnlineTransducerModelConfig transducer = OnlineTransducerModelConfig.builder()
                    .setEncoder(enc.getAbsolutePath())
                    .setDecoder(dec.getAbsolutePath())
                    .setJoiner(join.getAbsolutePath())
                    .build();
            OnlineModelConfig model = OnlineModelConfig.builder()
                    .setTransducer(transducer)
                    .setTokens(tok.getAbsolutePath())
                    .setNumThreads(1)
                    .setDebug(false)
                    .setProvider("cpu")
                    .setModelType("zipformer2")
                    .build();
            FeatureConfig feat = FeatureConfig.builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setFeatureDim(80)
                    .setDither(0f)
                    .build();
            KeywordSpotterConfig config = KeywordSpotterConfig.builder()
                    .setFeatureConfig(feat)
                    .setOnlineModelConfig(model)
                    .setKeywordsFile(kw.getAbsolutePath())
                    .setKeywordsScore(KEYWORDS_SCORE)
                    .setKeywordsThreshold(KEYWORDS_THRESHOLD)
                    .setMaxActivePaths(16)
                    .setNumTrailingBlanks(2)
                    .build();
            Log.i(TAG, "creating KeywordSpotter enc=" + enc.getName()
                    + " size=" + enc.length()
                    + " threshold=" + KEYWORDS_THRESHOLD
                    + " score=" + KEYWORDS_SCORE);
            kws = new KeywordSpotter(config);
            Log.i(TAG, "KeywordSpotter ready (zipformer2) threshold=" + KEYWORDS_THRESHOLD);
            return true;
        } catch (UnsatisfiedLinkError e) {
            nativeBroken = true;
            Log.e(TAG, "native broken: " + e.getMessage(), e);
            return false;
        } catch (Throwable e) {
            String msg = e.getMessage();
            Throwable cause = e.getCause();
            Log.e(TAG, "init failed: " + e.getClass().getSimpleName()
                    + " msg=" + msg
                    + (cause != null ? " cause=" + cause.getClass().getSimpleName()
                    + ":" + cause.getMessage() : ""), e);
            if (e instanceof Error) nativeBroken = true;
            releaseSpotter();
            return false;
        }
    }

    public void start() {
        wantRun = true;
        routeChanged = false;
        if (running) return;
        if (!ensureLoaded()) return;
        if (ActivityCompat.checkSelfPermission(app, android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        PocketWakeGuard.start(app);
        thread = new Thread(this::loop, "sherpa-kws");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        KwsCrashGuard.onKwsStarting(app);
        thread.start();
    }

    public void stop() {
        wantRun = false;
        if (!routeChanged) {
            routeChanged = true;
        }
        Thread t = thread;
        if (t != null) {
            try {
                t.join(1500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        thread = null;
        running = false;
        PocketWakeGuard.stop();
    }

    public void release() {
        stop();
        releaseSpotter();
    }

    private void onExternalRouteChange() {
        Log.i(TAG, "audio route changed — stopping capture for restart");
        routeChanged = true;
        main.post(() -> {
            if (listener != null) {
                listener.onAudioRouteChanged();
            }
        });
    }

    private void releaseSpotter() {
        if (stream != null) {
            try { stream.release(); } catch (Exception ignored) {}
            stream = null;
        }
        if (kws != null) {
            try { kws.release(); } catch (Exception ignored) {}
            kws = null;
        }
    }

    private String routeDescription() {
        return routeManager != null ? routeManager.describeRoute() : "PHONE_BUILTIN source=VOICE_RECOGNITION";
    }

    private void loop() {
        running = true;
        try {
            if (!openMic()) {
                Log.w(TAG, "mic open failed route=" + routeDescription());
                return;
            }
            readCount = 0;
            KwsDiagnostics.logSessionStart(routeDescription());
            stream = kws.createStream();
            if (stream == null || stream.getPtr() == 0L) {
                Log.e(TAG, "createStream failed");
                return;
            }
            audioRecord.startRecording();
            int bufferSize = Math.max(1, (int) (INTERVAL_SEC * SAMPLE_RATE));
            short[] buffer = new short[bufferSize];
            int emptyStreak = 0;
            float[] recentRms = new float[PEAK_RMS_WINDOW];
            java.util.Arrays.fill(recentRms, -96f);
            int recentRmsIdx = 0;
            boolean hadSpeech = false;
            int quietAfterSpeech = 0;
            while (wantRun && !routeChanged) {
                // Ne plus skipper les frames si média : sinon « Pégase » n'est jamais scorée.
                if (MediaPlaybackGuard.isOtherAudioPlaying(app)) {
                    KwsDiagnostics.maybeLogMediaActive(routeDescription());
                }
                int ret = audioRecord.read(buffer, 0, buffer.length);
                if (ret < 0) {
                    Log.w(TAG, "AudioRecord.read error=" + ret + " route=" + routeDescription()
                            + (ret == AudioRecord.ERROR_DEAD_OBJECT ? " DEAD_OBJECT"
                            : ret == AudioRecord.ERROR_INVALID_OPERATION ? " INVALID_OP"
                            : ret == AudioRecord.ERROR_BAD_VALUE ? " BAD_VALUE" : ""));
                    if (ret == AudioRecord.ERROR_DEAD_OBJECT
                            || ret == AudioRecord.ERROR_INVALID_OPERATION) {
                        break;
                    }
                    emptyStreak++;
                    if (emptyStreak > 40) break;
                    sleepQuiet(40);
                    continue;
                }
                if (ret == 0) {
                    emptyStreak++;
                    if (emptyStreak > 100) {
                        Log.w(TAG, "AudioRecord empty streak — exit for restart route="
                                + routeDescription());
                        break;
                    }
                    sleepQuiet(20);
                    continue;
                }
                emptyStreak = 0;
                float rmsDb = KwsDiagnostics.computeRmsDb(buffer, ret);
                recentRms[recentRmsIdx % PEAK_RMS_WINDOW] = rmsDb;
                recentRmsIdx++;
                float peakRmsDb = rmsDb;
                for (float r : recentRms) {
                    if (r > peakRmsDb) peakRmsDb = r;
                }
                readCount++;
                if (readCount % PROBE_EVERY_READS == 0) {
                    KwsDiagnostics.maybeLogProbe(routeDescription(), rmsDb, ret);
                }
                float[] samples = new float[ret];
                for (int i = 0; i < ret; i++) {
                    samples[i] = buffer[i] / 32768.0f;
                }

                if (rmsDb > SPEECH_RMS_DB) {
                    hadSpeech = true;
                    quietAfterSpeech = 0;
                } else if (hadSpeech && rmsDb < QUIET_RMS_DB) {
                    quietAfterSpeech++;
                    if (quietAfterSpeech >= SILENCE_RECREATE_FRAMES) {
                        OnlineStream fresh = recreateStream(stream);
                        if (fresh != null) {
                            stream = fresh;
                            KwsDiagnostics.logStreamReset(routeDescription(), "recreate_after_silence");
                        }
                        hadSpeech = false;
                        quietAfterSpeech = 0;
                    }
                }

                stream.acceptWaveform(samples, SAMPLE_RATE);
                boolean decoded = false;
                while (wantRun && !routeChanged && kws.isReady(stream)) {
                    decoded = true;
                    kws.decode(stream);
                    KeywordSpotterResult result = kws.getResult(stream);
                    String kw = result != null ? result.getKeyword() : null;
                    String[] tokens = result != null ? result.getTokens() : null;
                    float[] timestamps = result != null ? result.getTimestamps() : null;
                    if (kw != null && !kw.trim().isEmpty()) {
                        kws.reset(stream);
                        hadSpeech = false;
                        quietAfterSpeech = 0;
                        final String detected = kw.trim();
                        // Alias courts / composés ambigus : gate RMS.
                        // PEGASE plein saute le gate (HIT souvent sur queue soft).
                        if (isCompoundWakeKeyword(detected) && peakRmsDb < MIN_COMPOUND_HIT_RMS_DB) {
                            KwsDiagnostics.logHitRejectedRms(
                                    routeDescription(), peakRmsDb, detected, tokens,
                                    MIN_COMPOUND_HIT_RMS_DB);
                            continue;
                        }
                        if (!isStrongWakeKeyword(detected) && peakRmsDb < MIN_HIT_RMS_DB) {
                            KwsDiagnostics.logHitRejectedRms(
                                    routeDescription(), peakRmsDb, detected, tokens, MIN_HIT_RMS_DB);
                            continue;
                        }
                        if (PocketWakeGuard.shouldSuppressWake(app)) {
                            KwsDiagnostics.logHitRejectedPocket(
                                    routeDescription(), peakRmsDb, detected, tokens);
                            continue;
                        }
                        KwsDiagnostics.logHit(routeDescription(), peakRmsDb, detected, tokens, timestamps);
                        wantRun = false;
                        main.post(() -> {
                            if (listener != null) listener.onKeywordDetected(detected);
                        });
                        return;
                    }
                    KwsDiagnostics.logDecodeReadyNoHit(routeDescription(), rmsDb, tokens, timestamps);
                }
                if (!decoded) {
                    KwsDiagnostics.maybeLogSpeechNoTokens(routeDescription(), rmsDb);
                }
            }
            if (routeChanged && wantRun) {
                Log.i(TAG, "loop exit for route change — watchdog will restart");
            }
        } catch (UnsatisfiedLinkError e) {
            nativeBroken = true;
            Log.e(TAG, "native in loop", e);
            KwsDiagnostics.logLoopError(routeDescription(), "native", e.getMessage());
        } catch (Throwable e) {
            Log.e(TAG, "loop error", e);
            KwsDiagnostics.logLoopError(routeDescription(), e.getClass().getSimpleName(),
                    e.getMessage());
        } finally {
            closeMic();
            if (stream != null) {
                try { stream.release(); } catch (Exception ignored) {}
                stream = null;
            }
            running = false;
            PocketWakeGuard.stop();
        }
    }

    private boolean openMic() {
        synchronized (captureLock) {
            // SCO tenu par VoiceService (ensureWakeServiceScoHold) — pas de prepare/release ici.
            int source = routeManager != null
                    ? routeManager.getAudioSource()
                    : android.media.MediaRecorder.AudioSource.MIC;
            int min = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) return false;
            audioRecord = new AudioRecord(
                    source,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(min * 2, SAMPLE_RATE / 5));
            if (routeManager != null) {
                routeManager.applyPreferredDevice(audioRecord);
            }
            boolean ok = audioRecord.getState() == AudioRecord.STATE_INITIALIZED;
            if (ok) {
                Log.i(TAG, "mic open " + routeDescription());
            } else {
                Log.e(TAG, "AudioRecord not initialized source=" + source);
            }
            return ok;
        }
    }

    private void closeMic() {
        long t0 = android.os.SystemClock.elapsedRealtime();
        synchronized (captureLock) {
            if (audioRecord != null) {
                try {
                    audioRecord.stop();
                } catch (Exception ignored) {}
                try {
                    audioRecord.release();
                } catch (Exception ignored) {}
                audioRecord = null;
            }
            // Ne pas releaseBluetoothSco : hold service wake reste actif jusqu'à stop/destroy.
        }
        try {
            org.json.JSONObject f = new org.json.JSONObject();
            f.put("backend", "sherpa");
            f.put("close_mic_ms", android.os.SystemClock.elapsedRealtime() - t0);
            f.put("released_sco", false);
            WakeToSttTrace.mark(app, "kws_close_mic_done", f);
        } catch (Exception ignored) {}
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Mots-clés complets (sans préfixe Hey/Ok) — fiables, pas de gate RMS. */
    private static boolean isStrongWakeKeyword(String keyword) {
        if (keyword == null) return false;
        String k = keyword.trim().toUpperCase(java.util.Locale.US);
        if (isCompoundWakeKeyword(k)) return false;
        return k.equals("PEGASE")
                || k.equals("PEGASE_CHARS")
                || k.equals("PEGA_SE")
                || k.equals("PEGASUS")
                || k.equals("PEGAZE");
    }

    /** Préfixes conversationnels — plus stricts (faux positifs en discussion). */
    private static boolean isCompoundWakeKeyword(String keyword) {
        if (keyword == null) return false;
        String k = keyword.trim().toUpperCase(java.util.Locale.US);
        return k.startsWith("HEY_")
                || k.startsWith("OK_")
                || k.startsWith("BONJOUR_");
    }

    private OnlineStream recreateStream(OnlineStream old) {
        try {
            if (old != null) {
                try { old.release(); } catch (Exception ignored) {}
            }
            OnlineStream fresh = kws.createStream();
            if (fresh == null || fresh.getPtr() == 0L) {
                Log.e(TAG, "recreateStream failed");
                return null;
            }
            return fresh;
        } catch (Throwable e) {
            Log.e(TAG, "recreateStream error", e);
            return null;
        }
    }
}
