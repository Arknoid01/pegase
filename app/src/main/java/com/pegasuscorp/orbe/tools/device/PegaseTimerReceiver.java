package com.pegasuscorp.orbe.tools.device;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.pegasuscorp.orbe.MainActivity;
import com.pegasuscorp.orbe.R;
import com.pegasuscorp.orbe.diag.PegaseDiagLog;

/**
 * Fin de minuteur Pégase — notification alarme (IMPORTANCE_HIGH + son TYPE_ALARM)
 * même écran verrouillé / Doze.
 */
public class PegaseTimerReceiver extends BroadcastReceiver {

    private static final String TAG = "PegaseTimer";

    public static final String ACTION_FIRE = "com.pegasuscorp.orbe.action.TIMER_FIRE";

    /**
     * Nouveau channel id : Android ne met pas à jour importance/son d'un channel existant.
     */
    public static final String CHANNEL_ID = "pegase_timers_v2";
    public static final String CHANNEL_NAME = "Minuteurs Pégase";

    private static final int NOTIF_ID = 0x7E11_E202;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        String action = intent.getAction();
        if (action != null && !ACTION_FIRE.equals(action)) return;
        int seconds = intent.getIntExtra(PegaseTimerScheduler.EXTRA_SECONDS, 0);
        String label = intent.getStringExtra(PegaseTimerScheduler.EXTRA_LABEL);
        showTimerFinishedNotification(context, seconds, label);
    }

    /** Point d'entrée testable (Robolectric / instrumentation). */
    public static Notification showTimerFinishedNotification(
            Context context, int seconds, String label) {
        Context app = context.getApplicationContext();
        ensureChannel(app);

        PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wl = null;
        if (pm != null) {
            wl = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, "pegase:timer_fire");
            wl.acquire(10_000L);
        }

        try {
            boolean locked = isKeyguardLocked(app);
            boolean dndBlocks = isDndBlockingAlarms(app);
            Log.i(TAG, "timer_fire seconds=" + seconds
                    + " locked=" + locked
                    + " dndBlocksAlarms=" + dndBlocks);

            String title = "Minuteur terminé";
            String body = TextUtils.isEmpty(label)
                    ? (seconds > 0
                    ? "Les " + TimerTool.formatLabel(seconds) + " sont écoulées."
                    : "Ton minuteur Pégase est terminé.")
                    : label;

            Intent open = new Intent(app, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent contentPi = PendingIntent.getActivity(
                    app,
                    NOTIF_ID,
                    open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            Uri alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (alarmSound == null) {
                alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            }

            NotificationCompat.Builder b = new NotificationCompat.Builder(app, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_pegase)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setCategory(NotificationCompat.CATEGORY_ALARM)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setAutoCancel(true)
                    .setContentIntent(contentPi)
                    .setSound(alarmSound)
                    .setVibrate(new long[]{0, 400, 200, 400, 200, 400})
                    .setDefaults(NotificationCompat.DEFAULT_LIGHTS);

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                b.setDefaults(NotificationCompat.DEFAULT_ALL);
            }

            Notification notification = b.build();
            notification.flags |= Notification.FLAG_INSISTENT;

            NotificationManagerCompat.from(app).notify(NOTIF_ID, notification);
            try {
                org.json.JSONObject f = new org.json.JSONObject();
                f.put("seconds", seconds);
                f.put("locked", locked);
                f.put("dnd_blocks", dndBlocks);
                f.put("label", label == null ? "" : label);
                PegaseDiagLog.kws(app, "timer_fire", f);
            } catch (Exception ignored) {}
            return notification;
        } finally {
            if (wl != null && wl.isHeld()) {
                try {
                    wl.release();
                } catch (Exception ignored) {}
            }
        }
    }

    public static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) return;

        Uri alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        if (alarmSound == null) {
            alarmSound = Settings.System.DEFAULT_ALARM_ALERT_URI;
        }
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();

        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Sonnerie de fin de minuteur (écran verrouillé / Doze)");
        ch.enableVibration(true);
        ch.setVibrationPattern(new long[]{0, 400, 200, 400, 200, 400});
        ch.enableLights(true);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        ch.setSound(alarmSound, attrs);
        // Les alarmes peuvent contourner le mode Ne pas déranger (si l'utilisateur l'autorise).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ch.setAllowBubbles(false);
        }
        try {
            ch.setBypassDnd(true);
        } catch (Exception ignored) {}
        nm.createNotificationChannel(ch);
    }

    /** Diagnostic DND : true si la politique coupe aussi les alarmes. */
    public static boolean isDndBlockingAlarms(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false;
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) return false;
        int filter = nm.getCurrentInterruptionFilter();
        if (filter == NotificationManager.INTERRUPTION_FILTER_ALL
                || filter == NotificationManager.INTERRUPTION_FILTER_UNKNOWN) {
            return false;
        }
        // NONE bloque tout ; PRIORITY dépend de la policy (alarms souvent OK).
        if (filter == NotificationManager.INTERRUPTION_FILTER_NONE) return true;
        NotificationManager.Policy policy = nm.getNotificationPolicy();
        if (policy == null) return false;
        return (policy.priorityCategories
                & NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS) == 0;
    }

    public static boolean isKeyguardLocked(Context context) {
        KeyguardManager km =
                (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        return km != null && km.isKeyguardLocked();
    }

    /** Channel importance (tests). */
    public static int channelImportance(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return NotificationManager.IMPORTANCE_HIGH;
        }
        ensureChannel(context);
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) return NotificationManager.IMPORTANCE_NONE;
        NotificationChannel ch = nm.getNotificationChannel(CHANNEL_ID);
        return ch != null ? ch.getImportance() : NotificationManager.IMPORTANCE_NONE;
    }

    public static boolean channelHasSound(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true;
        ensureChannel(context);
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) return false;
        NotificationChannel ch = nm.getNotificationChannel(CHANNEL_ID);
        return ch != null && ch.getSound() != null;
    }
}
