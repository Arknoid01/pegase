package com.pegasuscorp.orbe.voice;

import android.content.Context;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Vérifie que c'est bien le propriétaire avant d'activer Pégase.
 */
public final class SpeakerVerifyGate {

    public interface Callback {
        void onVerified();
        void onRejected();
        void onSkipped();
    }

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private SpeakerVerifyGate() {}

    public static boolean isRequired(Context context) {
        SpeakerProfileStore profile = SpeakerProfileStore.getInstance(context);
        return profile.isRequireOwnerVoice()
                && profile.getSampleCount() >= 3
                && SpeakerModelStore.isModelReady(context);
    }

    public static void runAfterPrompt(Context context, Runnable onRecordStart,
                                      Callback callback) {
        if (!isRequired(context)) {
            callback.onSkipped();
            return;
        }
        if (onRecordStart != null) onRecordStart.run();
        IO.execute(() -> runVerify(context, AudioCapture.recordSeconds(2), callback));
    }

    /** Vérifie un échantillon déjà capturé (wake word). */
    public static boolean verifyBlocking(Context context, float[] samples) {
        if (!isRequired(context)) return true;
        if (samples == null || samples.length < AudioCapture.SAMPLE_RATE / 4) return true;
        final boolean[] out = new boolean[]{true};
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        runVerify(context, samples, new Callback() {
            @Override public void onVerified() { out[0] = true; latch.countDown(); }
            @Override public void onRejected() { out[0] = false; latch.countDown(); }
            @Override public void onSkipped() { out[0] = true; latch.countDown(); }
        });
        try { latch.await(5, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        return out[0];
    }

    private static void runVerify(Context context, float[] samples, Callback callback) {
        SpeakerVerifierEngine.getInstance().verifySamples(context, samples,
                new SpeakerVerifierEngine.VerifyCallback() {
                    @Override
                    public void onResult(boolean matched) {
                        if (matched) callback.onVerified();
                        else callback.onRejected();
                    }

                    @Override
                    public void onError(String message) {
                        callback.onSkipped();
                    }
                });
    }
}
