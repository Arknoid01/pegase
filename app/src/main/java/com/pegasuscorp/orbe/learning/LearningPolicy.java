package com.pegasuscorp.orbe.learning;

import android.content.Context;
import android.content.SharedPreferences;

import com.pegasuscorp.orbe.intentions.IntentionPolicy;

import java.time.LocalDate;

/**
 * Garde-fous : pas d'écriture silencieuse des vérités ; 1 notif learning / jour ; quiet hours.
 */
public final class LearningPolicy {

    public static final int MAX_DAILY_NOTIFS = 1;
    public static final long DETECTOR_COOLDOWN_MS = 20L * 60L * 60L * 1000L;
    public static final long REFUSE_COOLDOWN_MS = 30L * 24L * 60L * 60L * 1000L;
    public static final long SNOOZE_MS = 4L * 60L * 60L * 1000L;

    private static final String PREFS = "pegase_learning";
    private static final String KEY_LAST_DETECTOR = "last_detector_run_ms";
    private static final String KEY_LAST_NOTIF = "last_notif_ms";
    private static final String KEY_DAILY_NOTIF_COUNT = "daily_notif_count";
    private static final String KEY_DAILY_DATE = "daily_notif_date";
    private static final String KEY_ENABLED = "enabled";

    private LearningPolicy() {}

    public static boolean isEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_ENABLED, true);
    }

    public static void setEnabled(Context ctx, boolean enabled) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    public static boolean canRunDetectors(Context ctx) {
        if (ctx == null || !isEnabled(ctx)) return false;
        long last = prefs(ctx).getLong(KEY_LAST_DETECTOR, 0L);
        return last <= 0L || (System.currentTimeMillis() - last) >= DETECTOR_COOLDOWN_MS;
    }

    public static void markDetectorsRan(Context ctx) {
        prefs(ctx).edit().putLong(KEY_LAST_DETECTOR, System.currentTimeMillis()).apply();
    }

    public static boolean canNotify(Context ctx) {
        if (ctx == null || !isEnabled(ctx)) return false;
        if (IntentionPolicy.isQuietHours(ctx, System.currentTimeMillis())) return false;
        rollDaily(ctx);
        return prefs(ctx).getInt(KEY_DAILY_NOTIF_COUNT, 0) < MAX_DAILY_NOTIFS;
    }

    public static void markNotified(Context ctx) {
        rollDaily(ctx);
        SharedPreferences p = prefs(ctx);
        int count = p.getInt(KEY_DAILY_NOTIF_COUNT, 0) + 1;
        p.edit()
                .putInt(KEY_DAILY_NOTIF_COUNT, count)
                .putLong(KEY_LAST_NOTIF, System.currentTimeMillis())
                .putString(KEY_DAILY_DATE, LocalDate.now().toString())
                .apply();
    }

    public static long refuseQuietUntil() {
        return System.currentTimeMillis() + REFUSE_COOLDOWN_MS;
    }

    public static long snoozeQuietUntil() {
        return System.currentTimeMillis() + SNOOZE_MS;
    }

    static void clearAll(Context ctx) {
        prefs(ctx).edit().clear().apply();
    }

    private static void rollDaily(Context ctx) {
        String today = LocalDate.now().toString();
        SharedPreferences p = prefs(ctx);
        if (!today.equals(p.getString(KEY_DAILY_DATE, ""))) {
            p.edit()
                    .putInt(KEY_DAILY_NOTIF_COUNT, 0)
                    .putString(KEY_DAILY_DATE, today)
                    .apply();
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
