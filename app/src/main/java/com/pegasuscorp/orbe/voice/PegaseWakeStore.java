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
    private static final String KEY_OWW_THRESHOLD = "oww_threshold";
    /** Adresse MAC du casque visé — vide = n'importe quel HFP connecté. */
    private static final String KEY_HFP_MAC = "hfp_mac";
    /** Seuil OWW — modèle v2 hard-neg ; 0.5 = défaut openWakeWord. */
    public static final float DEFAULT_OWW_THRESHOLD = 0.50f;

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

    /**
     * Casque visé pour le wake. Vide (défaut) = tout profil HFP connecté est accepté,
     * ce qui inclut les kits mains-libres de voiture — d'où la possibilité de cibler.
     */
    public static String getHfpMac(Context context) {
        String mac = prefs(context).getString(KEY_HFP_MAC, "");
        return mac == null ? "" : mac.trim().toUpperCase(java.util.Locale.ROOT);
    }

    /** {@code null} ou vide pour revenir à « n'importe quel casque ». */
    public static void setHfpMac(Context context, String mac) {
        String clean = mac == null ? "" : mac.trim().toUpperCase(java.util.Locale.ROOT);
        prefs(context).edit().putString(KEY_HFP_MAC, clean).apply();
    }

    public static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    public static float getOwwThreshold(Context context) {
        SharedPreferences p = prefs(context);
        if (!p.contains(KEY_OWW_THRESHOLD)) {
            return DEFAULT_OWW_THRESHOLD;
        }
        float t = p.getFloat(KEY_OWW_THRESHOLD, DEFAULT_OWW_THRESHOLD);
        // Anciens seuils v1 (0.86–0.88) trop hauts pour le modèle hard-neg.
        if (t >= 0.80f) {
            t = DEFAULT_OWW_THRESHOLD;
            p.edit().putFloat(KEY_OWW_THRESHOLD, t).apply();
        }
        return t;
    }

    public static void setOwwThreshold(Context context, float threshold) {
        float t = Math.max(0.40f, Math.min(0.95f, threshold));
        prefs(context).edit().putFloat(KEY_OWW_THRESHOLD, t).apply();
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
