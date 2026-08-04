package com.pegasuscorp.orbe.copilot;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

/**
 * Alerte mid-session quand le service a11y tombe — pas seulement à la prochaine ui_action.
 */
public final class A11yDownAlert {

    private static final String CHANNEL_ID = "pegase_a11y";
    private static final int NOTIF_ID = 0xA11001;
    private static volatile long lastAlertMs;

    private A11yDownAlert() {}

    public static void notifyServiceDown(Context ctx, String reason) {
        if (ctx == null) return;
        Context app = ctx.getApplicationContext();
        long now = System.currentTimeMillis();
        if (now - lastAlertMs < 30_000L) return;
        lastAlertMs = now;

        try {
            ensureChannel(app);
            Intent settings = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pi = PendingIntent.getActivity(app, NOTIF_ID, settings,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            String body = "Accessibilité Pégase coupée — le copilote ne peut plus cliquer."
                    + (reason != null && !reason.isEmpty() ? " (" + reason + ")" : "");
            NotificationCompat.Builder b = new NotificationCompat.Builder(app, CHANNEL_ID)
                    .setSmallIcon(com.pegasuscorp.orbe.R.drawable.ic_stat_pegase)
                    .setContentTitle("Copilote indisponible")
                    .setContentText(body)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .setContentIntent(pi);
            NotificationManager nm = app.getSystemService(NotificationManager.class);
            if (nm != null) nm.notify(NOTIF_ID, b.build());
        } catch (Exception ignored) {
        }

        try {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                try {
                    Toast.makeText(app,
                            "Accessibilité Pégase coupée — réactive-la dans les réglages.",
                            Toast.LENGTH_LONG).show();
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }

    private static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Accessibilité Pégase", NotificationManager.IMPORTANCE_DEFAULT);
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(ch);
    }
}
