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
    /** Seuil Sherpa — abaissé pour « Pégase » en français (modèle GigaSpeech EN). */
    private static final float KEYWORDS_THRESHOLD = 0.18f;
    /** ~2 s entre deux logs probe (100 ms × 20). */
    private static final int PROBE_EVERY_READS = 20;

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
                    .setKeywordsScore(1.0f)
                    .setKeywordsThreshold(KEYWORDS_THRESHOLD)
                    .setMaxActivePaths(4)
                    .setNumTrailingBlanks(1)
                    .build();
            Log.i(TAG, "creating KeywordSpotter enc=" + enc.getName()
                    + " size=" + enc.length());
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
            while (wantRun && !routeChanged) {
                if (MediaPlaybackGuard.isOtherAudioPlaying(app)) {
                    sleepQuiet(400);
                    continue;
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
                readCount++;
                if (readCount % PROBE_EVERY_READS == 0) {
                    KwsDiagnostics.maybeLogProbe(routeDescription(), rmsDb, ret);
                }
                float[] samples = new float[ret];
                for (int i = 0; i < ret; i++) {
                    samples[i] = buffer[i] / 32768.0f;
                }
                stream.acceptWaveform(samples, SAMPLE_RATE);
                while (wantRun && !routeChanged && kws.isReady(stream)) {
                    kws.decode(stream);
                    KeywordSpotterResult result = kws.getResult(stream);
                    String kw = result != null ? result.getKeyword() : null;
                    String[] tokens = result != null ? result.getTokens() : null;
                    float[] timestamps = result != null ? result.getTimestamps() : null;
                    if (kw != null && !kw.trim().isEmpty()) {
                        kws.reset(stream);
                        final String detected = kw.trim();
                        KwsDiagnostics.logHit(routeDescription(), rmsDb, detected, tokens, timestamps);
                        wantRun = false;
                        main.post(() -> {
                            if (listener != null) listener.onKeywordDetected(detected);
                        });
                        return;
                    }
                    KwsDiagnostics.logDecodeReadyNoHit(routeDescription(), rmsDb, tokens, timestamps);
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
        }
    }

    private boolean openMic() {
        synchronized (captureLock) {
            if (routeManager != null && !routeManager.prepareCapture()) {
                Log.w(TAG, "prepareCapture failed — fallback micro téléphone");
                routeManager.forcePhoneBuiltin();
            }
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
            if (routeManager != null) {
                routeManager.releaseCapture();
            }
        }
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
