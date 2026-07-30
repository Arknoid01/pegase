package com.pegasuscorp.orbe.prefetch;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.util.Calendar;
import java.util.Locale;

/**
 * Alarme matin {@link AlarmManager#RTC_WAKEUP} → {@link PrefetchAlarmReceiver}
 * → {@link PrefetchService#run}. Heure configurable (défaut 6h40).
 */
public final class PrefetchScheduler {

    public static final String ACTION_PREFETCH = "com.pegasuscorp.orbe.action.PREFETCH_ALARM";
    public static final int DEFAULT_HOUR = 6;
    public static final int DEFAULT_MINUTE = 40;

    private static final String PREFS = "prefetch_scheduler";
    private static final String KEY_HOUR = "hour";
    private static final String KEY_MINUTE = "minute";
    private static final int REQUEST_CODE = 6401;

    private PrefetchScheduler() {}

    public static int getHour(Context ctx) {
        return Math.max(0, Math.min(23, prefs(ctx).getInt(KEY_HOUR, DEFAULT_HOUR)));
    }

    public static int getMinute(Context ctx) {
        return Math.max(0, Math.min(59, prefs(ctx).getInt(KEY_MINUTE, DEFAULT_MINUTE)));
    }

    /** Persiste l'heure et (re)programme l'alarme. */
    public static void setAlarmTime(Context ctx, int hour, int minute) {
        int h = Math.max(0, Math.min(23, hour));
        int m = Math.max(0, Math.min(59, minute));
        prefs(ctx).edit().putInt(KEY_HOUR, h).putInt(KEY_MINUTE, m).apply();
        schedule(ctx);
    }

    public static String formatTimeLabel(Context ctx) {
        return String.format(Locale.FRANCE, "%02d:%02d", getHour(ctx), getMinute(ctx));
    }

    /** Programme (ou remplace) l'alarme quotidienne. */
    @SuppressWarnings("deprecation")
    public static void schedule(Context ctx) {
        if (ctx == null) return;
        Context app = ctx.getApplicationContext();
        AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = pendingIntent(app);
        long triggerAt = nextTriggerMillis(getHour(app), getMinute(app), System.currentTimeMillis());
        try {
            am.setRepeating(AlarmManager.RTC_WAKEUP, triggerAt, AlarmManager.INTERVAL_DAY, pi);
        } catch (Exception ignored) {
            // certains OEM / restrictions exact-alarm
        }
    }

    /** Idempotent — à appeler au démarrage app + après BOOT_COMPLETED. */
    public static void ensureScheduled(Context ctx) {
        schedule(ctx);
    }

    public static void cancel(Context ctx) {
        if (ctx == null) return;
        Context app = ctx.getApplicationContext();
        AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        am.cancel(pendingIntent(app));
    }

    /** Prochaine occurrence de hour:minute (aujourd'hui si encore à venir, sinon demain). */
    static long nextTriggerMillis(int hour, int minute, long nowMs) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(nowMs);
        cal.set(Calendar.HOUR_OF_DAY, hour);
        cal.set(Calendar.MINUTE, minute);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        if (cal.getTimeInMillis() <= nowMs) {
            cal.add(Calendar.DAY_OF_YEAR, 1);
        }
        return cal.getTimeInMillis();
    }

    private static PendingIntent pendingIntent(Context app) {
        Intent intent = new Intent(app, PrefetchAlarmReceiver.class)
                .setAction(ACTION_PREFETCH);
        return PendingIntent.getBroadcast(app, REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
