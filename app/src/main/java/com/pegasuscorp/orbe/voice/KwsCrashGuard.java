package com.pegasuscorp.orbe.voice;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * Détecte un crash loop KWS (process {@code :voice} qui meurt &lt; 8 s après start)
 * et désactive Sherpa pour stabiliser la notif FGS.
 */
public final class KwsCrashGuard {

    private static final String TAG = "KwsCrashGuard";
    private static final String PREF = "kws_crash_guard";
    private static final String KEY_FAILS = "fails";
    private static final String KEY_START_MS = "start_ms";
    private static final String KEY_CONFIG_GEN = "config_gen";
    private static final long CRASH_WINDOW_MS = 8_000L;
    private static final int MAX_FAILS = 5;

    private KwsCrashGuard() {}

    /** Remet le compteur à 0 si la config KWS a changé (nouvelle génération). */
    static void bumpConfigGeneration(Context ctx, int generation) {
        SharedPreferences p = prefs(ctx);
        if (p.getInt(KEY_CONFIG_GEN, 0) == generation) return;
        p.edit()
                .putInt(KEY_CONFIG_GEN, generation)
                .putInt(KEY_FAILS, 0)
                .putLong(KEY_START_MS, 0L)
                .apply();
        Log.i(TAG, "reset after config gen=" + generation);
    }

    static boolean shouldDisableKws(Context ctx) {
        return prefs(ctx).getInt(KEY_FAILS, 0) >= MAX_FAILS;
    }

    /**
     * Redémarrage volontaire (changement route, watchdog) — ne pas compter comme crash.
     */
    static void onPlannedRestart(Context ctx) {
        prefs(ctx).edit().putLong(KEY_START_MS, 0L).apply();
    }

    /** Appeler juste avant de démarrer le thread KWS. */
    static void onKwsStarting(Context ctx) {
        SharedPreferences p = prefs(ctx);
        long now = System.currentTimeMillis();
        long last = p.getLong(KEY_START_MS, 0L);
        int fails = p.getInt(KEY_FAILS, 0);
        if (last > 0L && now - last < CRASH_WINDOW_MS) {
            fails++;
            Log.w(TAG, "KWS died quickly — fail #" + fails);
        }
        p.edit().putInt(KEY_FAILS, fails).putLong(KEY_START_MS, now).apply();
    }

    /** Appeler après ~15 s d'écoute KWS stable. */
    static void onKwsHealthy(Context ctx) {
        prefs(ctx).edit().putInt(KEY_FAILS, 0).putLong(KEY_START_MS, 0L).apply();
    }

    static void reset(Context ctx) {
        prefs(ctx).edit().clear().apply();
        Log.i(TAG, "manual reset");
    }

    /** Exposé pour l'UI diagnostic (réinitialiser après faux positifs crash guard). */
    public static void resetForUser(Context ctx) {
        reset(ctx);
        Log.i(TAG, "manual reset");
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }
}
