package com.pegasuscorp.orbe.voice;

import android.content.Context;
import android.media.AudioManager;

/**
 * Monte le volume média à 90 % quand Pégase est actif,
 * puis restaure le niveau précédent à la fin de la session.
 */
public final class AssistantVolumeGuard {

    private static final float ACTIVE_VOLUME_RATIO = 0.9f;

    private static boolean active;
    private static int savedMusicVolume = -1;

    private AssistantVolumeGuard() {}

    public static synchronized void activate(Context context) {
        if (active) return;
        AudioManager am = audioManager(context);
        if (am == null) return;

        savedMusicVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        setStreamToRatio(am, AudioManager.STREAM_MUSIC, ACTIVE_VOLUME_RATIO);
        active = true;
    }

    public static synchronized void deactivate(Context context) {
        if (!active) return;
        AudioManager am = audioManager(context);
        if (am != null && savedMusicVolume >= 0) {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, savedMusicVolume, 0);
        }
        savedMusicVolume = -1;
        active = false;
    }

    public static boolean isActive() {
        return active;
    }

    private static void setStreamToRatio(AudioManager am, int stream, float ratio) {
        int max = am.getStreamMaxVolume(stream);
        if (max <= 0) return;
        int target = Math.max(1, Math.round(max * ratio));
        am.setStreamVolume(stream, target, 0);
    }

    private static AudioManager audioManager(Context context) {
        if (context == null) return null;
        return (AudioManager) context.getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
    }
}
