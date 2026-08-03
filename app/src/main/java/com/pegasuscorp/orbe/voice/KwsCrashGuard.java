package com.pegasuscorp.orbe.voice;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.pegasuscorp.orbe.diag.PegaseDiagLog;

import org.json.JSONObject;

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
    /**
     * Fenêtre « mort rapide » après start KWS — aussi délai COOLDOWN après
     * {@link WakeCoordinator#onCrashGuardTripped()} (sémantique crash-loop, pas anti-écho TTS).
     */
    public static final long CRASH_WINDOW_MS = 8_000L;
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
        log(ctx, "crash_guard_config_bump", generation, 0, false);
    }

    /** Exposé pour le dashboard debug. */
    public static boolean shouldDisableKws(Context ctx) {
        boolean disabled = fails(ctx) >= MAX_FAILS;
        if (disabled) {
            log(ctx, "crash_guard_tripped", generation(ctx), fails(ctx), true);
        }
        return disabled;
    }

    /**
     * Redémarrage volontaire (changement route, watchdog) — ne pas compter comme crash.
     */
    static void onPlannedRestart(Context ctx) {
        prefs(ctx).edit().putLong(KEY_START_MS, 0L).apply();
        log(ctx, "crash_guard_planned_restart", generation(ctx), fails(ctx), false);
    }

    /** Appeler juste avant de démarrer le thread KWS. */
    static void onKwsStarting(Context ctx) {
        SharedPreferences p = prefs(ctx);
        long now = System.currentTimeMillis();
        long last = p.getLong(KEY_START_MS, 0L);
        int failCount = p.getInt(KEY_FAILS, 0);
        if (last > 0L && now - last < CRASH_WINDOW_MS) {
            failCount++;
            Log.w(TAG, "KWS died quickly — fail #" + failCount);
            log(ctx, "crash_guard_quick_death", generation(ctx), failCount, false);
        }
        p.edit().putInt(KEY_FAILS, failCount).putLong(KEY_START_MS, now).apply();
    }

    /** Appeler après ~15 s d'écoute KWS stable. */
    static void onKwsHealthy(Context ctx) {
        prefs(ctx).edit().putInt(KEY_FAILS, 0).putLong(KEY_START_MS, 0L).apply();
        log(ctx, "crash_guard_healthy", generation(ctx), 0, false);
    }

    static void reset(Context ctx) {
        prefs(ctx).edit().clear().apply();
        Log.i(TAG, "manual reset");
        log(ctx, "crash_guard_reset", 0, 0, false);
    }

    /** Exposé pour l'UI diagnostic (réinitialiser après faux positifs crash guard). */
    public static void resetForUser(Context ctx) {
        reset(ctx);
        Log.i(TAG, "manual reset");
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private static int generation(Context ctx) {
        return prefs(ctx).getInt(KEY_CONFIG_GEN, 0);
    }

    private static int fails(Context ctx) {
        return prefs(ctx).getInt(KEY_FAILS, 0);
    }

    private static void log(Context ctx, String event, int configGen, int fails, boolean tripped) {
        try {
            JSONObject f = new JSONObject();
            f.put("config_gen", configGen);
            f.put("fails", fails);
            f.put("max_fails", MAX_FAILS);
            f.put("tripped", tripped);
            PegaseDiagLog.kws(ctx, event, f);
        } catch (Exception ignored) {}
    }
}
