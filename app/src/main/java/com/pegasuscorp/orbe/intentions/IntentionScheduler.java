package com.pegasuscorp.orbe.intentions;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

/**
 * Tick de rattrapage (~90 min) — pas la source principale des edges.
 */
public final class IntentionScheduler {

    public static final String ACTION_TICK = "com.pegasuscorp.orbe.intentions.TICK";
    private static final int REQUEST_CODE = 64043;
    private static final long INTERVAL_MS = 90L * 60L * 1000L;

    private IntentionScheduler() {}

    public static void ensureScheduled(Context ctx) {
        if (ctx == null) return;
        Context app = ctx.getApplicationContext();
        IntentionEventReceiver.register(app);
        scheduleTick(app);
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
        } catch (Exception ignored) {
            try {
                am.setRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + INTERVAL_MS,
                        INTERVAL_MS, pi);
            } catch (Exception ignored2) {}
        }
    }

    public static void cancel(Context ctx) {
        if (ctx == null) return;
        Context app = ctx.getApplicationContext();
        AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(tickPi(app));
    }

    private static PendingIntent tickPi(Context app) {
        Intent i = new Intent(app, IntentionTickReceiver.class).setAction(ACTION_TICK);
        return PendingIntent.getBroadcast(app, REQUEST_CODE, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Receiver du tick. */
    public static final class IntentionTickReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (context == null) return;
            IntentionEvaluator.evaluateSensors(context.getApplicationContext());
        }
    }
}
