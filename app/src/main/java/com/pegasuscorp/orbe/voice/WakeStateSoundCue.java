package com.pegasuscorp.orbe.voice;

import android.content.Context;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Handler;
import android.os.Looper;

/**
 * Sons discrets pour l'état d'écoute wake / PTT (v3 P1).
 */
public final class WakeStateSoundCue {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile ToneGenerator tone;
    private static volatile long lastCueMs;

    private WakeStateSoundCue() {}

    public static void playListeningOn(Context context) {
        play(context, ToneGenerator.TONE_PROP_ACK, 90);
    }

    public static void playListeningOff(Context context) {
        play(context, ToneGenerator.TONE_PROP_NACK, 70);
    }

    public static void playWakeProblem(Context context) {
        play(context, ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 120);
    }

    private static void play(Context context, int toneType, int durationMs) {
        if (context == null) return;
        long now = System.currentTimeMillis();
        if (now - lastCueMs < 250L) return;
        lastCueMs = now;
        MAIN.post(() -> {
            try {
                ToneGenerator g = ensureTone(context);
                if (g != null) g.startTone(toneType, durationMs);
            } catch (Exception ignored) {
            }
        });
    }

    private static ToneGenerator ensureTone(Context context) {
        ToneGenerator g = tone;
        if (g != null) return g;
        try {
            int stream = AudioManager.STREAM_NOTIFICATION;
            g = new ToneGenerator(stream, 55);
            tone = g;
            return g;
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static void release() {
        ToneGenerator g = tone;
        tone = null;
        if (g != null) {
            try {
                g.release();
            } catch (Exception ignored) {
            }
        }
    }
}
