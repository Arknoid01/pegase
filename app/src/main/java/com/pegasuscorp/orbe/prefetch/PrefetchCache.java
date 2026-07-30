package com.pegasuscorp.orbe.prefetch;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

/**
 * Cache local des prefetches matin avec TTL.
 * Clés : weather (3h), nasa (24h), boucherie (24h), diag (session / jour).
 */
public final class PrefetchCache {

    public static final String KEY_WEATHER = "weather";
    public static final String KEY_NASA = "nasa";
    public static final String KEY_BOUCHERIE = "boucherie";
    public static final String KEY_DIAG = "diag";
    public static final String KEY_CUSTOM = "custom_routines";

    public static final long TTL_WEATHER_MS = 3L * 60 * 60 * 1000;
    public static final long TTL_NASA_MS = 24L * 60 * 60 * 1000;
    public static final long TTL_BOUCHERIE_MS = 24L * 60 * 60 * 1000;
    /** Valide jusqu'à minuit (ou override horloge en test). */
    public static final long TTL_DIAG_SESSION_MS = 24L * 60 * 60 * 1000;
    public static final long TTL_CUSTOM_MS = 24L * 60 * 60 * 1000;

    private static final String PREFS = "prefetch_cache";

    /** Horloge injectable pour les tests TTL. */
    static volatile Long nowOverrideMs = null;

    private PrefetchCache() {}

    public static String customKey(String routineId) {
        return "custom:" + (routineId != null ? routineId : "");
    }

    public static void put(Context ctx, String key, String value) {
        if (ctx == null || TextUtils.isEmpty(key) || value == null) return;
        prefs(ctx).edit()
                .putString(valueKey(key), value)
                .putLong(tsKey(key), now())
                .apply();
    }

    /** @return texte frais, ou {@code null} si absent / TTL expiré. */
    public static String get(Context ctx, String key, long ttlMs) {
        if (ctx == null || TextUtils.isEmpty(key)) return null;
        SharedPreferences p = prefs(ctx);
        String value = p.getString(valueKey(key), null);
        if (value == null) return null;
        long ts = p.getLong(tsKey(key), 0L);
        if (ttlMs >= 0 && now() - ts > ttlMs) return null;
        return value;
    }

    public static boolean isFresh(Context ctx, String key, long ttlMs) {
        return get(ctx, key, ttlMs) != null;
    }

    public static void clear(Context ctx) {
        if (ctx == null) return;
        prefs(ctx).edit().clear().apply();
    }

    /** Visible pour tests. */
    static void clearKey(Context ctx, String key) {
        if (ctx == null || key == null) return;
        prefs(ctx).edit()
                .remove(valueKey(key))
                .remove(tsKey(key))
                .apply();
    }

    static long now() {
        Long o = nowOverrideMs;
        return o != null ? o : System.currentTimeMillis();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String valueKey(String key) {
        return "v_" + key;
    }

    private static String tsKey(String key) {
        return "t_" + key;
    }
}
