package com.pegasuscorp.orbe.voice;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.util.Log;

import com.k2fsa.sherpa.onnx.GenerationConfig;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Synthèse vocale Piper via sherpa-onnx (voix française locale).
 */
public class PiperTtsEngine {

    private static final String TAG = "PiperTts";

    private final Context appContext;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private OfflineTts tts;
    private AudioTrack track;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final AtomicBoolean speaking = new AtomicBoolean(false);
    private final AtomicInteger utteranceId = new AtomicInteger(0);
    private volatile boolean nativeBroken = false;

    public PiperTtsEngine(Context context) {
        appContext = context.getApplicationContext();
    }

    public boolean isReady() {
        return !nativeBroken && PiperModelStore.isModelReady(appContext);
    }

    public boolean isNativeBroken() {
        return nativeBroken;
    }

    public boolean isSpeaking() {
        return speaking.get();
    }

    public void stop() {
        utteranceId.incrementAndGet();
        stopped.set(true);
        if (track != null) {
            try {
                track.pause();
                track.flush();
                track.stop();
            } catch (Exception ignored) {}
        }
        speaking.set(false);
    }

    public void speak(String text, Runnable onComplete) {
        if (!isReady() || text == null || text.trim().isEmpty()) {
            if (onComplete != null) main.post(onComplete);
            return;
        }
        final int id = utteranceId.incrementAndGet();
        io.execute(() -> {
            speaking.set(true);
            stopped.set(false);
            try {
                ensureTts();
                if (tts == null) return;
                initTrack(tts.getSampleRate());
                GenerationConfig gen = new GenerationConfig();
                gen.setSid(0);
                gen.setSpeed(SpeechRulesStore.getInstance(appContext).getSnapshot().speed);
                tts.generateWithConfigAndCallback(text.trim(), gen, samples -> {
                    if (stopped.get() || id != utteranceId.get()) return 0;
                    if (samples != null && samples.length > 0 && track != null) {
                        track.write(samples, 0, samples.length, AudioTrack.WRITE_BLOCKING);
                    }
                    return (stopped.get() || id != utteranceId.get()) ? 0 : 1;
                });
            } catch (UnsatisfiedLinkError e) {
                markNativeBroken(e);
            } catch (Throwable e) {
                Log.e(TAG, "Erreur Piper", e);
                if (e instanceof Error) markNativeBroken(e);
            } finally {
                speaking.set(false);
                if (onComplete != null && id == utteranceId.get()) {
                    main.post(onComplete);
                }
            }
        });
    }

    private void ensureTts() {
        if (tts != null || nativeBroken) return;
        File dir = PiperModelStore.resolveModelDir(appContext);
        if (dir == null) return;
        File onnx = PiperModelStore.findOnnx(dir);
        if (onnx == null) return;
        File tokens = new File(dir, "tokens.txt");
        File dataDir = new File(dir, "espeak-ng-data");

        try {
            ModelTuning tuning = readModelTuning(dir, onnx);
            OfflineTtsVitsModelConfig vits = OfflineTtsVitsModelConfig.builder()
                    .setModel(onnx.getAbsolutePath())
                    .setTokens(tokens.getAbsolutePath())
                    .setDataDir(dataDir.getAbsolutePath())
                    .setNoiseScale(tuning.noiseScale)
                    .setNoiseScaleW(tuning.noiseScaleW)
                    .setLengthScale(tuning.lengthScale)
                    .build();
            OfflineTtsModelConfig model = OfflineTtsModelConfig.builder()
                    .setVits(vits)
                    .setNumThreads(2)
                    .setDebug(false)
                    .build();
            OfflineTtsConfig config = OfflineTtsConfig.builder().setModel(model).build();
            tts = new OfflineTts(config);
        } catch (UnsatisfiedLinkError e) {
            markNativeBroken(e);
        } catch (Throwable e) {
            Log.e(TAG, "Init Piper impossible", e);
            if (e instanceof Error) markNativeBroken(e);
        }
    }

    private void markNativeBroken(Throwable e) {
        nativeBroken = true;
        PiperModelStore.setUsePiper(appContext, false);
        Log.e(TAG, "Piper désactivé — fallback TTS Android", e);
    }

    private static ModelTuning readModelTuning(File dir, File onnx) {
        ModelTuning defaults = new ModelTuning(0.667f, 0.8f, 1.03f);
        File json = new File(dir, onnx.getName() + ".json");
        if (!json.exists()) return defaults;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                new FileInputStream(json), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            JSONObject o = new JSONObject(sb.toString());
            float noise = (float) o.optDouble("noise_scale", defaults.noiseScale);
            float noiseW = (float) o.optDouble("noise_scale_w", defaults.noiseScaleW);
            float length = (float) o.optDouble("length_scale", defaults.lengthScale);
            // Légèrement plus lent = articulation française un peu plus nette
            return new ModelTuning(noise, noiseW, length * 1.03f);
        } catch (Exception e) {
            return defaults;
        }
    }

    private static final class ModelTuning {
        final float noiseScale;
        final float noiseScaleW;
        final float lengthScale;

        ModelTuning(float noiseScale, float noiseScaleW, float lengthScale) {
            this.noiseScale = noiseScale;
            this.noiseScaleW = noiseScaleW;
            this.lengthScale = lengthScale;
        }
    }

    private void initTrack(int sampleRate) {
        if (track != null) {
            try {
                track.stop();
                track.release();
            } catch (Exception ignored) {}
        }
        int min = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_FLOAT);
        // Buffer plus large + silence d'amorçage : sinon play() à vide
        // coupe le début du premier mot (underrun AudioTrack).
        int buf = Math.max(min, min * 3);
        AudioAttributes attr = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();
        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build();
        track = new AudioTrack(attr, format, buf, AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE);
        int leadMs = 120;
        int leadSamples = Math.max(1, sampleRate * leadMs / 1000);
        float[] silence = new float[leadSamples];
        track.write(silence, 0, silence.length, AudioTrack.WRITE_BLOCKING);
        track.play();
    }

    public void release() {
        stop();
        if (tts != null) {
            try {
                tts.release();
            } catch (Exception ignored) {}
            tts = null;
        }
        io.shutdownNow();
    }

    /** Recharge le modèle après changement de voix ou import. */
    public void reload() {
        stop();
        if (track != null) {
            try {
                track.release();
            } catch (Exception ignored) {}
            track = null;
        }
        if (tts != null) {
            try {
                tts.release();
            } catch (Exception ignored) {}
            tts = null;
        }
    }

    /** Initialise Piper en arrière-plan ; désactive Piper si la couche native plante. */
    public void probeAsync() {
        if (!isReady() || nativeBroken) return;
        io.execute(() -> {
            try {
                ensureTts();
            } catch (UnsatisfiedLinkError e) {
                markNativeBroken(e);
            } catch (Throwable e) {
                Log.e(TAG, "Probe Piper impossible", e);
                if (e instanceof Error) markNativeBroken(e);
            }
        });
    }
}
