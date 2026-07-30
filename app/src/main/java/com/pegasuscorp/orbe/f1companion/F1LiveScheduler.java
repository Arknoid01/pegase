package com.pegasuscorp.orbe.f1companion;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

import com.pegasuscorp.orbe.intentions.IntentionEvaluator;

import org.json.JSONObject;

/**
 * Live GP : poll fréquent pendant la course (~90 s), sinon ~20 min.
 */
public final class F1LiveScheduler {

    public static final String ACTION_TICK = "com.pegasuscorp.orbe.f1.LIVE_TICK";
    private static final String TAG = "F1LiveScheduler";
    private static final int REQUEST_CODE = 64045;
    private static final long IDLE_MS = 20L * 60L * 1000L;
    private static final long LIVE_MS = 90L * 1000L;

    private F1LiveScheduler() {}

    public static void ensureScheduled(Context ctx) {
        if (ctx == null) return;
        scheduleNext(ctx.getApplicationContext(), IDLE_MS);
    }

    public static void scheduleNext(Context app, long delayMs) {
        AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = tickPi(app);
        long when = SystemClock.elapsedRealtime() + Math.max(30_000L, delayMs);
        try {
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, when, pi);
        } catch (Exception e) {
            try {
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, when, pi);
            } catch (Exception ignored) {
                Log.w(TAG, "schedule", ignored);
            }
        }
    }

    public static void cancel(Context ctx) {
        if (ctx == null) return;
        AlarmManager am = (AlarmManager) ctx.getApplicationContext()
                .getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(tickPi(ctx.getApplicationContext()));
    }

    private static PendingIntent tickPi(Context app) {
        Intent i = new Intent(app, LiveTickReceiver.class).setAction(ACTION_TICK);
        return PendingIntent.getBroadcast(app, REQUEST_CODE, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static long nextDelayMs(Context app) {
        if (!F1LiveStore.isEnabled(app)) return IDLE_MS;
        try {
            JSONObject s = OpenF1Service.findLiveRaceSession();
            if (s != null) return LIVE_MS;
        } catch (Exception ignored) {}
        return IDLE_MS;
    }

    public static final class LiveTickReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (context == null) return;
            final PendingResult pending = goAsync();
            final Context app = context.getApplicationContext();
            new Thread(() -> {
                try {
                    IntentionEvaluator.checkF1Live(app);
                } catch (Exception e) {
                    Log.w(TAG, "tick", e);
                } finally {
                    try {
                        scheduleNext(app, nextDelayMs(app));
                    } catch (Exception ignored) {}
                    pending.finish();
                }
            }, "f1-live-tick").start();
        }
    }
}
