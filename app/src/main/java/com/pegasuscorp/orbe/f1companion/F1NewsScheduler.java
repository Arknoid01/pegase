package com.pegasuscorp.orbe.f1companion;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

import com.pegasuscorp.orbe.intentions.IntentionEvaluator;

/**
 * Poll RSS F1 ~15 min (inexact) — réseau hors thread principal.
 */
public final class F1NewsScheduler {

    public static final String ACTION_TICK = "com.pegasuscorp.orbe.f1.NEWS_TICK";
    private static final String TAG = "F1NewsScheduler";
    private static final int REQUEST_CODE = 64044;
    private static final long INTERVAL_MS = 15L * 60L * 1000L;

    private F1NewsScheduler() {}

    public static void ensureScheduled(Context ctx) {
        if (ctx == null) return;
        scheduleTick(ctx.getApplicationContext());
    }

    @SuppressWarnings("deprecation")
    public static void scheduleTick(Context app) {
        AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = tickPi(app);
        try {
            am.setInexactRepeating(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + INTERVAL_MS,
                    INTERVAL_MS,
                    pi);
        } catch (Exception e) {
            Log.w(TAG, "schedule", e);
            try {
                am.setRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + INTERVAL_MS,
                        INTERVAL_MS, pi);
            } catch (Exception ignored) {}
        }
    }

    public static void cancel(Context ctx) {
        if (ctx == null) return;
        AlarmManager am = (AlarmManager) ctx.getApplicationContext()
                .getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(tickPi(ctx.getApplicationContext()));
    }

    private static PendingIntent tickPi(Context app) {
        Intent i = new Intent(app, NewsTickReceiver.class).setAction(ACTION_TICK);
        return PendingIntent.getBroadcast(app, REQUEST_CODE, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    public static final class NewsTickReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (context == null) return;
            final PendingResult pending = goAsync();
            final Context app = context.getApplicationContext();
            new Thread(() -> {
                try {
                    IntentionEvaluator.checkF1News(app);
                } catch (Exception e) {
                    Log.w(TAG, "tick", e);
                } finally {
                    pending.finish();
                }
            }, "f1-news-tick").start();
        }
    }
}
