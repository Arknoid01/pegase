package com.pegasuscorp.orbe.voice;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Coupe-micro global : bloque wake, commandes rapides et discussion.
 */
public final class VoiceMuteStore {

    private static final String PREFS = "voice_mute";
    private static final String KEY_MUTED = "muted";

    private VoiceMuteStore() {}

    public static boolean isMuted(Context context) {
        return prefs(context).getBoolean(KEY_MUTED, false);
    }

    public static void setMuted(Context context, boolean muted) {
        prefs(context).edit().putBoolean(KEY_MUTED, muted).apply();
        PegaseWakeController.setMicGloballyMuted(muted);
    }

    public static void syncController(Context context) {
        PegaseWakeController.setMicGloballyMuted(isMuted(context));
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
