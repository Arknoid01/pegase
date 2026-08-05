package com.pegasuscorp.orbe.voice;

import android.content.Context;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Handler;
import android.os.Looper;

/**
 * Sons discrets pour l'état d'écoute wake / PTT (v3 P1).
 * <p>
 * Sous SCO Bluetooth, {@link AudioManager#STREAM_NOTIFICATION} est souvent
 * inaudible (routé hors du casque) — on bascule sur {@link AudioManager#STREAM_VOICE_CALL}.
 */
public final class WakeStateSoundCue {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile ToneGenerator tone;
    private static volatile int toneStream = -1;
    private static volatile long lastCueMs;

    private WakeStateSoundCue() {}

    public static void playListeningOn(Context context) {
        play(context, ToneGenerator.TONE_PROP_ACK, 110);
    }

    public static void playListeningOff(Context context) {
        play(context, ToneGenerator.TONE_PROP_NACK, 80);
    }

    public static void playWakeProblem(Context context) {
        play(context, ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 120);
    }

    private static void play(Context context, int toneType, int durationMs) {
        if (context == null) return;
        long now = System.currentTimeMillis();
        if (now - lastCueMs < 250L) return;
        lastCueMs = now;
        final Context app = context.getApplicationContext();
        MAIN.post(() -> {
            try {
                ToneGenerator g = ensureTone(app);
                if (g != null) g.startTone(toneType, durationMs);
            } catch (Exception ignored) {
            }
        });
    }

    private static int preferredStream(Context context) {
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am != null && (am.isBluetoothScoOn() || am.getMode() == AudioManager.MODE_IN_COMMUNICATION)) {
            return AudioManager.STREAM_VOICE_CALL;
        }
        // STREAM_MUSIC survit mieux au duck média que NOTIFICATION (souvent à 0).
        return AudioManager.STREAM_MUSIC;
    }

    private static ToneGenerator ensureTone(Context context) {
        int stream = preferredStream(context);
        ToneGenerator g = tone;
        if (g != null && toneStream == stream) return g;
        release();
        try {
            g = new ToneGenerator(stream, 80);
            tone = g;
            toneStream = stream;
            return g;
        } catch (RuntimeException e) {
            // Fallback notification si le stream voice/music est refusé.
            try {
                g = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70);
                tone = g;
                toneStream = AudioManager.STREAM_NOTIFICATION;
                return g;
            } catch (RuntimeException e2) {
                return null;
            }
        }
    }

    public static void release() {
        ToneGenerator g = tone;
        tone = null;
        toneStream = -1;
        if (g != null) {
            try {
                g.release();
            } catch (Exception ignored) {
            }
        }
    }
}
