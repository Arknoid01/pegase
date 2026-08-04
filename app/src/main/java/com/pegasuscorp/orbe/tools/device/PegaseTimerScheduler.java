package com.pegasuscorp.orbe.tools.device;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import com.pegasuscorp.orbe.MainActivity;
import com.pegasuscorp.orbe.diag.PegaseDiagLog;

import org.json.JSONObject;

/**
 * Minuteur Pégase — alarme exacte qui survit Doze / écran verrouillé.
 * Préfère {@link AlarmManager#setAlarmClock} (toujours exact, même sans
 * {@code SCHEDULE_EXACT_ALARM} utilisateur).
 */
public final class PegaseTimerScheduler {

    private static final String TAG = "PegaseTimer";

    public static final String EXTRA_LABEL = "timer_label";
    public static final String EXTRA_SECONDS = "timer_seconds";
    public static final String EXTRA_FIRE_AT_ELAPSED = "timer_fire_at_elapsed";

    /** Request code stable pour un seul minuteur actif à la fois (remplace le précédent). */
    private static final int REQ_CODE = 0x7E11_E201;
    private static final int REQ_SHOW = 0x7E11_E203;

    private PegaseTimerScheduler() {}

    /**
     * Planifie une alarme exacte qui réveille l'appareil.
     *
     * @return epoch millis de déclenchement, ou -1 si impossible
     */
    public static long schedule(Context context, int seconds, String label) {
        if (context == null || seconds <= 0) return -1L;
        Context app = context.getApplicationContext();
        AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return -1L;

        long triggerAtElapsed = SystemClock.elapsedRealtime() + seconds * 1000L;
        long triggerAtWall = System.currentTimeMillis() + seconds * 1000L;

        Intent intent = new Intent(app, PegaseTimerReceiver.class)
                .setAction(PegaseTimerReceiver.ACTION_FIRE)
                .putExtra(EXTRA_SECONDS, seconds)
                .putExtra(EXTRA_LABEL, label == null ? "" : label)
                .putExtra(EXTRA_FIRE_AT_ELAPSED, triggerAtElapsed);

        PendingIntent pi = PendingIntent.getBroadcast(
                app,
                REQ_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String method = "none";
        try {
            // setAlarmClock = exact sans dépendre de SCHEDULE_EXACT_ALARM (souvent refusé).
            Intent show = new Intent(app, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent showPi = PendingIntent.getActivity(
                    app,
                    REQ_SHOW,
                    show,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            AlarmManager.AlarmClockInfo clock =
                    new AlarmManager.AlarmClockInfo(triggerAtWall, showPi);
            am.setAlarmClock(clock, pi);
            method = "setAlarmClock";
            Log.i(TAG, "scheduled method=" + method
                    + " seconds=" + seconds + " atWall=" + triggerAtWall);
            logDiag(app, true, method, seconds, triggerAtWall, null);
            return triggerAtWall;
        } catch (Exception e) {
            Log.w(TAG, "setAlarmClock failed — fallback exact/inexact", e);
        }

        try {
            boolean canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                    || am.canScheduleExactAlarms();
            if (canExact) {
                am.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtElapsed, pi);
                method = "setExactAndAllowWhileIdle";
            } else {
                Log.w(TAG, "canScheduleExactAlarms=false — setAndAllowWhileIdle (inexact)");
                am.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtElapsed, pi);
                method = "setAndAllowWhileIdle";
            }
            Log.i(TAG, "scheduled method=" + method
                    + " seconds=" + seconds + " atWall=" + triggerAtWall
                    + " canExact=" + canExact);
            logDiag(app, true, method, seconds, triggerAtWall, null);
            return triggerAtWall;
        } catch (SecurityException e) {
            Log.e(TAG, "schedule denied", e);
            try {
                am.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtElapsed, pi);
                method = "setAndAllowWhileIdle_after_deny";
                logDiag(app, true, method, seconds, triggerAtWall, e.getMessage());
                return triggerAtWall;
            } catch (Exception e2) {
                Log.e(TAG, "fallback schedule failed", e2);
                logDiag(app, false, "failed", seconds, -1L, e2.getMessage());
                return -1L;
            }
        }
    }

    public static void cancel(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent intent = new Intent(app, PegaseTimerReceiver.class)
                .setAction(PegaseTimerReceiver.ACTION_FIRE);
        PendingIntent pi = PendingIntent.getBroadcast(
                app,
                REQ_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.cancel(pi);
    }

    /** Ouvre l’écran système « alarmes exactes » si la permission est refusée. */
    public static void promptExactAlarmSettingsIfNeeded(Context context) {
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null || am.canScheduleExactAlarms()) return;
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                    .setData(android.net.Uri.parse("package:" + context.getPackageName()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
        } catch (Exception e) {
            Log.w(TAG, "promptExactAlarmSettings", e);
        }
    }

    public static boolean canScheduleExact(Context context) {
        if (context == null) return true;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        return am != null && am.canScheduleExactAlarms();
    }

    /** Visible pour tests — construit le PendingIntent comme en prod. */
    static PendingIntent pendingIntentForTests(Context context, int seconds, String label) {
        Context app = context.getApplicationContext();
        Intent intent = new Intent(app, PegaseTimerReceiver.class)
                .setAction(PegaseTimerReceiver.ACTION_FIRE)
                .putExtra(EXTRA_SECONDS, seconds)
                .putExtra(EXTRA_LABEL, label == null ? "" : label);
        return PendingIntent.getBroadcast(
                app,
                REQ_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void logDiag(
            Context app, boolean ok, String method, int seconds, long atWall, String err) {
        try {
            JSONObject f = new JSONObject();
            f.put("ok", ok);
            f.put("method", method);
            f.put("seconds", seconds);
            f.put("at_wall", atWall);
            f.put("can_exact", canScheduleExact(app));
            if (err != null) f.put("error", err);
            PegaseDiagLog.kws(app, "timer_scheduled", f);
        } catch (Exception ignored) {}
    }
}
