package com.pegasuscorp.orbe.voice;

import android.content.Context;

import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingManager;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Vérification du locuteur via sherpa-onnx (WeSpeaker / ECAPA).
 */
public final class SpeakerVerifierEngine {

    public interface VerifyCallback {
        void onResult(boolean matched);
        void onError(String message);
    }

    private static SpeakerVerifierEngine instance;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private SpeakerEmbeddingExtractor extractor;
    private SpeakerEmbeddingManager manager;
    private int embeddingDim;

    private SpeakerVerifierEngine() {}

    public static synchronized SpeakerVerifierEngine getInstance() {
        if (instance == null) instance = new SpeakerVerifierEngine();
        return instance;
    }

    public synchronized boolean ensureReady(Context context) {
        if (extractor != null && manager != null) return true;
        if (!SpeakerModelStore.isModelReady(context)) return false;
        try {
            SpeakerEmbeddingExtractorConfig config =
                    SpeakerEmbeddingExtractorConfig.builder()
                            .setModel(SpeakerModelStore.modelFile(context).getAbsolutePath())
                            .setNumThreads(2)
                            .setDebug(false)
                            .build();
            extractor = new SpeakerEmbeddingExtractor(config);
            embeddingDim = extractor.getDim();
            manager = new SpeakerEmbeddingManager(embeddingDim);
            reloadProfile(context);
            return true;
        } catch (UnsatisfiedLinkError e) {
            release();
            return false;
        } catch (Exception e) {
            release();
            return false;
        }
    }

    public synchronized void reloadProfile(Context context) {
        if (manager == null) return;
        SpeakerProfileStore profile = SpeakerProfileStore.getInstance(context);
        try {
            manager.remove(SpeakerProfileStore.ownerId());
        } catch (Exception ignored) {}
        List<float[]> samples = profile.getSamples();
        if (samples.isEmpty()) return;
        float[][] arr = samples.toArray(new float[0][]);
        manager.add(SpeakerProfileStore.ownerId(), arr);
    }

    public void verifySamples(Context context, float[] samples, VerifyCallback callback) {
        io.execute(() -> {
            try {
                if (!ensureReady(context)) {
                    callback.onError("Modèle locuteur indisponible");
                    return;
                }
                SpeakerProfileStore profile = SpeakerProfileStore.getInstance(context);
                if (!profile.isEnrolled()) {
                    callback.onResult(true);
                    return;
                }
                float[] embedding = computeEmbedding(samples);
                if (embedding == null || embedding.length == 0) {
                    callback.onError("Échantillon audio trop court");
                    return;
                }
                boolean ok = manager.verify(
                        SpeakerProfileStore.ownerId(),
                        embedding,
                        profile.getThreshold());
                callback.onResult(ok);
            } catch (Exception e) {
                callback.onError(e.getMessage() != null ? e.getMessage() : "Erreur locuteur");
            }
        });
    }

    public void enrollSamples(Context context, float[] samples, EnrollCallback callback) {
        io.execute(() -> {
            try {
                if (!ensureReady(context)) {
                    callback.onError("Modèle locuteur indisponible");
                    return;
                }
                float[] embedding = computeEmbedding(samples);
                if (embedding == null || embedding.length == 0) {
                    callback.onError("Échantillon trop court — parle plus fort");
                    return;
                }
                SpeakerProfileStore.getInstance(context).addSample(embedding);
                reloadProfile(context);
                callback.onSuccess(SpeakerProfileStore.getInstance(context).getSampleCount());
            } catch (Exception e) {
                callback.onError(e.getMessage() != null ? e.getMessage() : "Erreur enrôlement");
            }
        });
    }

    private float[] computeEmbedding(float[] samples) {
        if (extractor == null || samples == null || samples.length < SAMPLE_RATE / 4) {
            return null;
        }
        OnlineStream stream = extractor.createStream();
        try {
            stream.acceptWaveform(samples, AudioCapture.SAMPLE_RATE);
            stream.inputFinished();
            if (!extractor.isReady(stream)) return null;
            return extractor.compute(stream);
        } finally {
            stream.release();
        }
    }

    private static final int SAMPLE_RATE = AudioCapture.SAMPLE_RATE;

    public synchronized void release() {
        if (manager != null) {
            try { manager.release(); } catch (Exception ignored) {}
            manager = null;
        }
        if (extractor != null) {
            try { extractor.release(); } catch (Exception ignored) {}
            extractor = null;
        }
    }

    public interface EnrollCallback {
        void onSuccess(int sampleCount);
        void onError(String message);
    }
}
