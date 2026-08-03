package com.pegasuscorp.orbe.voice;

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import java.nio.FloatBuffer;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

/**
 * Wake openWakeWord — pipeline 3 étages (mel → embedding → classifieur).
 * <p>
 * Capture audio via {@link KwsAudioRouteManager} (même chemin que Sherpa / STT) :
 * SCO tenu par {@code VoiceService} ({@code ensureWakeServiceScoHold}),
 * preferred device à l'open mic, restart sur changement de route.
 * <p>
 * Anti-FP : seuil haut, N hits consécutifs, warmup au démarrage, gate énergie.
 */
public final class OpenWakeWordEngine {

    public interface Listener {
        void onKeywordDetected(String keyword, float score);

        /** Casque BT / filaire branché ou débranché pendant l'écoute. */
        default void onAudioRouteChanged() {}
    }

    private static final String TAG = "OpenWakeWord";
    private static final int SAMPLE_RATE = 16_000;
    private static final int CHUNK_SAMPLES = 1280; // 80 ms
    /**
     * Contexte mel streaming openWakeWord : {@code n_samples + 160*3}.
     * Sans ce chevauchement, le score reste ~0 même si le modèle est bon.
     */
    private static final int MEL_CONTEXT_SAMPLES = 160 * 3; // 480
    private static final int EMBEDDING_INPUT_FRAMES = 76;
    private static final int EMBEDDING_DIM = 96;
    private static final int CLASSIFIER_INPUT_EMBEDDINGS = 16;
    /** Modèle v2 (hard-neg) : détection front montant classique. */
    private static final float DEFAULT_THRESHOLD = 0.50f;
    private static final long REFRACTORY_MS = 4_000L;
    private static final long GLOBAL_REFRACTORY_MS = 8_000L;
    private static volatile long sLastFireElapsedMs;
    private static final long WARMUP_MS = 2_000L;
    private static final int CONSECUTIVE_HITS = 3;
    private static final int PEAK_RMS_WINDOW = 15;
    private static final float MIN_PEAK_RMS = 250f;
    private static final float SUSPECT_HIGH_SCORE = 0.97f;
    private static final float SUSPECT_MAX_PEAK = 120f;
    private static final float SUSPECT_MAX_RMS = 60f;
    private static final float LOW_SCORE_RESET = 0.35f;

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;

    private KwsAudioRouteManager routeManager;
    private OrtEnvironment ortEnv;
    private OrtSession melSession;
    private OrtSession embedSession;
    private OrtSession wakeSession;
    private AudioRecord audioRecord;
    private Thread thread;
    private volatile boolean wantRun;
    private volatile boolean running;
    private volatile boolean loaded;
    private volatile boolean routeChanged;
    /** prepareCapture / releaseCapture uniquement depuis le thread OWW. */
    private final Object captureLock = new Object();
    private float threshold = DEFAULT_THRESHOLD;

    public OpenWakeWordEngine(Context context, Listener listener) {
        this.app = context.getApplicationContext();
        this.listener = listener;
        this.threshold = PegaseWakeStore.getOwwThreshold(this.app);
    }

    /** À appeler avant {@link #start()} (typiquement depuis {@code VoiceService.onCreate}). */
    public void setRouteManager(KwsAudioRouteManager routeManager) {
        this.routeManager = routeManager;
        if (routeManager != null) {
            routeManager.setRouteChangeListener(this::onExternalRouteChange);
        }
    }

    public boolean isReady() {
        return loaded && WakeOwwStore.isModelReady(app);
    }

    public boolean isRunning() {
        return running;
    }

    public void setThreshold(float threshold) {
        this.threshold = Math.max(0.05f, Math.min(0.99f, threshold));
    }

    public synchronized boolean ensureLoaded() {
        if (loaded) return true;
        if (!WakeOwwStore.isModelReady(app)) {
            Log.w(TAG, "models not ready");
            return false;
        }
        try {
            ortEnv = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            opts.setIntraOpNumThreads(2);
            melSession = ortEnv.createSession(WakeOwwStore.melFile(app).getAbsolutePath(), opts);
            embedSession = ortEnv.createSession(WakeOwwStore.embedFile(app).getAbsolutePath(), opts);
            wakeSession = ortEnv.createSession(
                    WakeOwwStore.classifierFile(app).getAbsolutePath(), opts);
            loaded = true;
            Log.i(TAG, "ONNX ready mel=" + WakeOwwStore.melFile(app).length()
                    + " embed=" + WakeOwwStore.embedFile(app).length()
                    + " cls=" + WakeOwwStore.classifierFile(app).length());
            return true;
        } catch (Throwable e) {
            Log.e(TAG, "ensureLoaded failed", e);
            releaseSessions();
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
        threshold = PegaseWakeStore.getOwwThreshold(app);
        PocketWakeGuard.start(app);
        thread = new Thread(this::loop, "oww-wake");
        thread.setPriority(Thread.MAX_PRIORITY);
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
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        thread = null;
        running = false;
        PocketWakeGuard.stop();
    }

    public void release() {
        stop();
        releaseSessions();
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

    private String routeDescription() {
        return routeManager != null
                ? routeManager.describeRoute()
                : "PHONE_BUILTIN source=MIC";
    }

    private void releaseSessions() {
        loaded = false;
        try { if (melSession != null) melSession.close(); } catch (Exception ignored) {}
        try { if (embedSession != null) embedSession.close(); } catch (Exception ignored) {}
        try { if (wakeSession != null) wakeSession.close(); } catch (Exception ignored) {}
        melSession = null;
        embedSession = null;
        wakeSession = null;
    }

    private void loop() {
        running = true;
        ArrayDeque<float[]> melBuffer = new ArrayDeque<>();
        ArrayDeque<float[]> embedBuffer = new ArrayDeque<>();
        // Buffer PCM int16-as-float pour le chevauchement mel streaming OWW.
        ArrayDeque<Float> rawPcm = new ArrayDeque<>(CHUNK_SAMPLES + MEL_CONTEXT_SAMPLES + 64);
        long lastFireMs = 0L;
        long sessionStartMs = System.currentTimeMillis();
        int consecutive = 0;
        long lastScoreLogMs = 0L;
        int emptyStreak = 0;
        boolean sawLowScore = true;
        float[] recentRms = new float[PEAK_RMS_WINDOW];
        java.util.Arrays.fill(recentRms, 0f);
        int recentRmsIdx = 0;
        try {
            if (!openMic()) {
                Log.w(TAG, "mic open failed route=" + routeDescription());
                return;
            }
            short[] pcm = new short[CHUNK_SAMPLES];
            float[] chunkFloat = new float[CHUNK_SAMPLES];
            audioRecord.startRecording();
            Log.i(TAG, "session_start threshold=" + threshold
                    + " consecutive=" + CONSECUTIVE_HITS
                    + " warmup_ms=" + WARMUP_MS
                    + " min_peak_rms=" + MIN_PEAK_RMS
                    + " mel_context=" + MEL_CONTEXT_SAMPLES
                    + " route={" + routeDescription() + "}");

            while (wantRun && !routeChanged) {
                if (MediaPlaybackGuard.isOtherAudioPlaying(app)) {
                    int skip = audioRecord.read(pcm, 0, CHUNK_SAMPLES);
                    if (skip < 0
                            && (skip == AudioRecord.ERROR_DEAD_OBJECT
                            || skip == AudioRecord.ERROR_INVALID_OPERATION)) {
                        Log.w(TAG, "AudioRecord dead during media skip route="
                                + routeDescription());
                        break;
                    }
                    melBuffer.clear();
                    embedBuffer.clear();
                    rawPcm.clear();
                    consecutive = 0;
                    sawLowScore = true;
                    continue;
                }
                int read = audioRecord.read(pcm, 0, CHUNK_SAMPLES);
                if (read < 0) {
                    Log.w(TAG, "AudioRecord.read error=" + read + " route=" + routeDescription()
                            + (read == AudioRecord.ERROR_DEAD_OBJECT ? " DEAD_OBJECT"
                            : read == AudioRecord.ERROR_INVALID_OPERATION ? " INVALID_OP"
                            : read == AudioRecord.ERROR_BAD_VALUE ? " BAD_VALUE" : ""));
                    if (read == AudioRecord.ERROR_DEAD_OBJECT
                            || read == AudioRecord.ERROR_INVALID_OPERATION) {
                        break;
                    }
                    emptyStreak++;
                    if (emptyStreak > 40) break;
                    sleepQuiet(40);
                    continue;
                }
                if (read == 0) {
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
                for (int i = 0; i < read; i++) {
                    chunkFloat[i] = pcm[i];
                    rawPcm.addLast(chunkFloat[i]);
                }
                // Pad short reads with silence so hop stays aligned.
                for (int i = read; i < CHUNK_SAMPLES; i++) {
                    chunkFloat[i] = 0f;
                    rawPcm.addLast(0f);
                }
                int maxRaw = CHUNK_SAMPLES + MEL_CONTEXT_SAMPLES;
                while (rawPcm.size() > maxRaw) {
                    rawPcm.removeFirst();
                }

                float rms = rms(chunkFloat, CHUNK_SAMPLES);
                recentRms[recentRmsIdx % PEAK_RMS_WINDOW] = rms;
                recentRmsIdx++;
                float peakRms = 0f;
                for (float r : recentRms) {
                    if (r > peakRms) peakRms = r;
                }

                // Mel sur les derniers chunk+context samples (comme AudioFeatures._streaming_melspectrogram).
                int melN = Math.min(rawPcm.size(), CHUNK_SAMPLES + MEL_CONTEXT_SAMPLES);
                float[] melInput = new float[melN];
                int skipRaw = rawPcm.size() - melN;
                int pi = 0;
                int ri = 0;
                for (float v : rawPcm) {
                    if (ri++ < skipRaw) continue;
                    melInput[pi++] = v;
                }
                float[][] melFrames = inferMel(melInput);
                if (melFrames == null) continue;
                for (float[] frame : melFrames) melBuffer.addLast(frame);
                // ~8 frames / chunk avec contexte ; garder une fenêtre confortable.
                while (melBuffer.size() > EMBEDDING_INPUT_FRAMES + 64) {
                    melBuffer.removeFirst();
                }
                if (melBuffer.size() < EMBEDDING_INPUT_FRAMES) continue;

                float[] embed = inferEmbedding(melBuffer);
                if (embed == null) continue;
                embedBuffer.addLast(embed);
                while (embedBuffer.size() > CLASSIFIER_INPUT_EMBEDDINGS + 8) {
                    embedBuffer.removeFirst();
                }
                if (embedBuffer.size() < CLASSIFIER_INPUT_EMBEDDINGS) continue;

                Float score = inferWake(embedBuffer);
                if (score == null) continue;

                if (score < LOW_SCORE_RESET) {
                    sawLowScore = true;
                }

                long now = System.currentTimeMillis();
                if (now - lastScoreLogMs > 2_000L) {
                    lastScoreLogMs = now;
                    Log.d(TAG, String.format(Locale.US,
                            "score=%.3f rms=%.0f peak=%.0f thr=%.2f consec=%d low=%s warmup=%s",
                            score, rms, peakRms, threshold, consecutive, sawLowScore,
                            (now - sessionStartMs < WARMUP_MS)));
                }

                if (now - sessionStartMs < WARMUP_MS) {
                    consecutive = 0;
                    continue;
                }
                if (score >= SUSPECT_HIGH_SCORE
                        && peakRms < SUSPECT_MAX_PEAK
                        && rms < SUSPECT_MAX_RMS) {
                    consecutive = 0;
                    continue;
                }

                if (score >= threshold && peakRms >= MIN_PEAK_RMS && sawLowScore) {
                    consecutive++;
                } else {
                    consecutive = 0;
                }
                if (consecutive < CONSECUTIVE_HITS) {
                    continue;
                }

                long elapsed = android.os.SystemClock.elapsedRealtime();
                if (now - lastFireMs < REFRACTORY_MS
                        || elapsed - sLastFireElapsedMs < GLOBAL_REFRACTORY_MS) {
                    Log.d(TAG, "hit_rejected_refractory score=" + score);
                    consecutive = 0;
                    continue;
                }
                if (PocketWakeGuard.shouldSuppressWake(app)) {
                    Log.i(TAG, "hit_rejected_pocket score=" + score);
                    consecutive = 0;
                    continue;
                }
                lastFireMs = now;
                sLastFireElapsedMs = elapsed;
                consecutive = 0;
                sawLowScore = false;
                Log.i(TAG, String.format(Locale.US,
                        "HIT keyword=HEY_PEGASE score=%.3f peak=%.0f threshold=%.2f mode=edge route={%s}",
                        score, peakRms, threshold, routeDescription()));
                wantRun = false;
                if (listener != null) listener.onKeywordDetected("HEY_PEGASE", score);
                return;
            }
            if (routeChanged && wantRun) {
                Log.i(TAG, "loop exit for route change — watchdog will restart");
            }
        } catch (Throwable e) {
            Log.e(TAG, "loop error", e);
        } finally {
            closeMic();
            running = false;
        }
    }

    private boolean openMic() {
        synchronized (captureLock) {
            // SCO tenu par VoiceService (ensureWakeServiceScoHold) — pas de prepare/release ici.
            int source = routeManager != null
                    ? routeManager.getAudioSource()
                    : MediaRecorder.AudioSource.MIC;
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
                    Math.max(min * 2, CHUNK_SAMPLES * 2 * 4));
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
            // Ne pas releaseBluetoothSco : hold service wake reste actif jusqu'à stop/destroy.
        }
    }

    private static float rms(float[] pcm, int n) {
        double sum = 0;
        int len = Math.min(n, pcm.length);
        for (int i = 0; i < len; i++) {
            double v = pcm[i];
            sum += v * v;
        }
        return len <= 0 ? 0f : (float) Math.sqrt(sum / len);
    }

    private float[][] inferMel(float[] pcmFloat) {
        if (melSession == null || ortEnv == null || pcmFloat == null || pcmFloat.length == 0) {
            return null;
        }
        try {
            OnnxTensor tensor = OnnxTensor.createTensor(
                    ortEnv, FloatBuffer.wrap(pcmFloat),
                    new long[]{1, pcmFloat.length});
            try {
                String inName = melSession.getInputNames().iterator().next();
                try (OrtSession.Result out = melSession.run(
                        Collections.singletonMap(inName, tensor))) {
                    Object value = out.get(0).getValue();
                    return scaleMelFrames(value);
                }
            } finally {
                tensor.close();
            }
        } catch (Exception e) {
            Log.w(TAG, "inferMel: " + e.getMessage());
            return null;
        }
    }

    private static float[][] scaleMelFrames(Object value) {
        float[][] frames;
        if (value instanceof float[][][][]) {
            float[][][][] a = (float[][][][]) value;
            frames = a[0][0];
        } else if (value instanceof float[][][]) {
            float[][][] a = (float[][][]) value;
            frames = a[0];
        } else if (value instanceof float[][]) {
            frames = (float[][]) value;
        } else {
            return null;
        }
        float[][] scaled = new float[frames.length][];
        for (int i = 0; i < frames.length; i++) {
            float[] f = frames[i];
            float[] s = new float[f.length];
            for (int j = 0; j < f.length; j++) {
                s[j] = (f[j] / 10f) + 2f;
            }
            scaled[i] = s;
        }
        return scaled;
    }

    private float[] inferEmbedding(ArrayDeque<float[]> mel) {
        if (embedSession == null || ortEnv == null) return null;
        try {
            float[] flat = new float[EMBEDDING_INPUT_FRAMES * 32];
            int start = mel.size() - EMBEDDING_INPUT_FRAMES;
            int p = 0;
            int idx = 0;
            for (float[] frame : mel) {
                if (idx++ < start) continue;
                System.arraycopy(frame, 0, flat, p, Math.min(32, frame.length));
                p += 32;
                if (p >= flat.length) break;
            }
            OnnxTensor tensor = OnnxTensor.createTensor(
                    ortEnv, FloatBuffer.wrap(flat),
                    new long[]{1, EMBEDDING_INPUT_FRAMES, 32, 1});
            try {
                String inName = embedSession.getInputNames().iterator().next();
                try (OrtSession.Result out = embedSession.run(
                        Collections.singletonMap(inName, tensor))) {
                    return extractEmbedding(out.get(0).getValue());
                }
            } finally {
                tensor.close();
            }
        } catch (Exception e) {
            Log.w(TAG, "inferEmbedding: " + e.getMessage());
            return null;
        }
    }

    private static float[] extractEmbedding(Object value) {
        if (value instanceof float[][][][]) {
            return ((float[][][][]) value)[0][0][0];
        }
        if (value instanceof float[][][]) {
            return ((float[][][]) value)[0][0];
        }
        if (value instanceof float[][]) {
            return ((float[][]) value)[0];
        }
        if (value instanceof float[]) {
            return (float[]) value;
        }
        return null;
    }

    private Float inferWake(ArrayDeque<float[]> emb) {
        if (wakeSession == null || ortEnv == null) return null;
        try {
            float[] flat = new float[CLASSIFIER_INPUT_EMBEDDINGS * EMBEDDING_DIM];
            int start = emb.size() - CLASSIFIER_INPUT_EMBEDDINGS;
            int p = 0;
            int idx = 0;
            for (float[] v : emb) {
                if (idx++ < start) continue;
                System.arraycopy(v, 0, flat, p, Math.min(EMBEDDING_DIM, v.length));
                p += EMBEDDING_DIM;
                if (p >= flat.length) break;
            }
            OnnxTensor tensor = OnnxTensor.createTensor(
                    ortEnv, FloatBuffer.wrap(flat),
                    new long[]{1, CLASSIFIER_INPUT_EMBEDDINGS, EMBEDDING_DIM});
            try {
                String inName = wakeSession.getInputNames().iterator().next();
                Map<String, OnnxTensor> inputs = new HashMap<>();
                inputs.put(inName, tensor);
                try (OrtSession.Result out = wakeSession.run(inputs)) {
                    return extractScore(out.get(0).getValue());
                }
            } finally {
                tensor.close();
            }
        } catch (Exception e) {
            Log.w(TAG, "inferWake: " + e.getMessage());
            return null;
        }
    }

    private static Float extractScore(Object value) {
        if (value instanceof float[][]) {
            return ((float[][]) value)[0][0];
        }
        if (value instanceof float[]) {
            return ((float[]) value)[0];
        }
        if (value instanceof Float) {
            return (Float) value;
        }
        return null;
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
