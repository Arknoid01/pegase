package com.pegasuscorp.orbe.voice;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;

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

    /**
     * Un autre son joue <b>et le micro l'entendrait</b>.
     *
     * <p>Le wake écoute le micro du téléphone. Si la lecture part dans un casque —
     * Bluetooth, filaire ou USB — le micro ne la capte pas : rien ne justifie de se
     * taire, et c'est précisément le cas d'usage principal (écouteurs aux oreilles,
     * musique en cours, on veut pouvoir appeler Pégase). On ne met en pause que si le
     * son sort par le haut-parleur du téléphone, où le micro le reprendrait.
     */
    @SuppressWarnings("deprecation")
    public static boolean isOtherAudioPlaying(Context context) {
        AudioManager am = (AudioManager) context.getApplicationContext()
                .getSystemService(Context.AUDIO_SERVICE);
        if (am == null || !am.isMusicActive()) return false;
        return !playsOnHeadset(am);
    }

    /** Sortie audio courante dirigée vers un casque plutôt que le haut-parleur. */
    private static boolean playsOnHeadset(AudioManager am) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false;
        try {
            for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                switch (d.getType()) {
                    case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
                    case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
                    case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                    case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
                    case AudioDeviceInfo.TYPE_USB_HEADSET:
                    case AudioDeviceInfo.TYPE_HEARING_AID:
                        return true;
                    default:
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                                && d.getType() == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                            return true;
                        }
                }
            }
        } catch (Exception ignored) {}
        return false;
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
