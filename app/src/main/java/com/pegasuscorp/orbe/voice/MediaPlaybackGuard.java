package com.pegasuscorp.orbe.voice;

import android.content.Context;
import android.media.AudioManager;

/**
 * Détecte si une autre app joue de l'audio (vidéo, musique, podcast).
 * Le wake en arrière-plan se met en pause pour ne pas couper la lecture.
 */
public final class MediaPlaybackGuard {

    /** Écart mini entre deux startListening — chaque start = hitch micro visible. */
    private static final long MIN_STT_RESTART_MS = 2_800L;
    private static final long MIN_STT_RESTART_GENTLE_MS = 4_500L;
    private static long lastSttStartMs;
    private static volatile boolean gentle = true;

    private MediaPlaybackGuard() {}

    /** Mode doux : rallonge l'écart entre sessions STT (moins de lag UI). */
    public static void setGentle(boolean on) {
        gentle = on;
    }

    public static boolean isGentle() {
        return gentle;
    }

    @SuppressWarnings("deprecation")
    public static boolean isOtherAudioPlaying(Context context) {
        AudioManager am = (AudioManager) context.getApplicationContext()
                .getSystemService(Context.AUDIO_SERVICE);
        return am != null && am.isMusicActive();
    }

    /** Évite de relancer SpeechRecognizer trop souvent (clignotement micro). */
    public static boolean canStartSttSession() {
        long now = System.currentTimeMillis();
        long min = gentle ? MIN_STT_RESTART_GENTLE_MS : MIN_STT_RESTART_MS;
        return now - lastSttStartMs >= min;
    }

    public static void markSttSessionStarted() {
        lastSttStartMs = System.currentTimeMillis();
    }
}
