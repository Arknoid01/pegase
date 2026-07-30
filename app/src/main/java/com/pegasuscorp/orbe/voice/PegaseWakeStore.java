package com.pegasuscorp.orbe.voice;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Préférence : écoute du mot « Pégase » en arrière-plan (SpeechRecognizer).
 */
public final class PegaseWakeStore {

    private static final String PREFS = "pegase_wake";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_GENTLE = "gentle_mode";
    private static final String KEY_LEGACY_CLEANUP = "local_wake_removed_v4";

    private PegaseWakeStore() {}

    /** Nettoie les anciennes prefs du wake local (keyword natif) supprimé. */
    public static void applyStartupSafety(Context context) {
        SharedPreferences p = prefs(context);
        if (!p.getBoolean(KEY_LEGACY_CLEANUP, false)) {
            p.edit()
                    .remove("local_wake")
                    .remove("local_wake_native_broken")
                    .remove("wake_chunk_fix_v3")
                    .putBoolean(KEY_LEGACY_CLEANUP, true)
                    .apply();
        }
    }

    public static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    /**
     * Mode doux (défaut) : sessions STT plus espacées → moins de lag UI
     * à chaque activation micro, wake un peu moins réactif.
     */
    public static boolean isGentleMode(Context context) {
        return prefs(context).getBoolean(KEY_GENTLE, true);
    }

    public static void setGentleMode(Context context, boolean gentle) {
        prefs(context).edit().putBoolean(KEY_GENTLE, gentle).apply();
        MediaPlaybackGuard.setGentle(gentle);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
