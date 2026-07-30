package com.pegasuscorp.orbe.learning;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

/**
 * Notification rare pour valider une hypothèse d'apprentissage.
 */
public final class LearningNotifier {

    public static final String CHANNEL_ID = "pegase_learning";
    public static final String EXTRA_CANDIDATE_ID = "learning_candidate_id";
    public static final String EXTRA_ACTION = "learning_action";
    public static final int NOTIFICATION_ID = 64043;

    public static final String ACTION_ACCEPT = "accept";
    public static final String ACTION_SNOOZE = "snooze";
    public static final String ACTION_REFUSE = "refuse";

    private LearningNotifier() {}

    public static void show(Context ctx, LearningCandidate candidate) {
        if (ctx == null || candidate == null || !candidate.isPending()) return;
        Context app = ctx.getApplicationContext();
        ensureChannel(app);
        NotificationManager nm = app.getSystemService(NotificationManager.class);
        if (nm == null) return;

        PendingIntent contentPi = actionPi(app, candidate.id, ACTION_ACCEPT);
        NotificationCompat.Builder b = new NotificationCompat.Builder(app, CHANNEL_ID)
                .setSmallIcon(com.pegasuscorp.orbe.R.drawable.ic_stat_pegase)
                .setContentTitle("Pégase · " + candidate.title())
                .setContentText(candidate.body())
                .setStyle(new NotificationCompat.BigTextStyle().bigText(candidate.body()))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(contentPi)
                .setOnlyAlertOnce(true)
                .addAction(0, "Oui", actionPi(app, candidate.id, ACTION_ACCEPT))
                .addAction(0, "Plus tard", actionPi(app, candidate.id, ACTION_SNOOZE))
                .addAction(0, "Non", actionPi(app, candidate.id, ACTION_REFUSE));

        nm.notify(NOTIFICATION_ID, b.build());
    }

    public static void cancel(Context ctx) {
        if (ctx == null) return;
        NotificationManager nm = ctx.getApplicationContext()
                .getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(NOTIFICATION_ID);
    }

    private static PendingIntent actionPi(Context app, String id, String action) {
        Intent i = new Intent(app, LearningActionReceiver.class)
                .setAction(LearningActionReceiver.ACTION)
                .putExtra(EXTRA_CANDIDATE_ID, id)
                .putExtra(EXTRA_ACTION, action);
        return PendingIntent.getBroadcast(
                app,
                (id + "|" + action).hashCode(),
                i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Apprentissages Pégase", NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription("Hypothèses à valider (horaires, suggestions)");
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(ch);
    }
}
