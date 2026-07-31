package com.pegasuscorp.orbe.voice;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import com.pegasuscorp.orbe.R;

/** Notifications discrètes pour actions refusées à l'écran verrouillé. */
final class LockScreenNotifier {

    private static final String CHANNEL_ID = "pegase_lock_actions";
    private static final int NOTIF_ID = 81;

    private LockScreenNotifier() {}

    static void postAgendaDenied(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        NotificationManager nm = app.getSystemService(NotificationManager.class);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Pégase verrouillé", NotificationManager.IMPORTANCE_MIN);
            ch.setDescription("Confirmations discrètes écran verrouillé");
            ch.setSound(null, null);
            ch.enableVibration(false);
            nm.createNotificationChannel(ch);
        }
        NotificationCompat.Builder b = new NotificationCompat.Builder(app, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_pegase)
                .setContentTitle("Pégase")
                .setContentText(LockScreenToolPolicy.AGENDA_DENIED_NOTIF)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setSilent(true)
                .setAutoCancel(true);
        nm.notify(NOTIF_ID, b.build());
    }
}
