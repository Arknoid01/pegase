package com.pegasuscorp.orbe.intentions;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

/**
 * Canal {@code pegase_intentions} — une notif stable, requestCodes id+action.
 */
public final class IntentionNotifier {

    public static final String CHANNEL_ID = "pegase_intentions";
    public static final String EXTRA_INTENTION_ID = "intention_id";
    public static final String EXTRA_ACTION = "intention_action";

    private IntentionNotifier() {}

    public static void show(Context ctx, IntentionCandidate candidate) {
        if (ctx == null || candidate == null) return;
        Context app = ctx.getApplicationContext();
        ensureChannel(app);
        NotificationManager nm = app.getSystemService(NotificationManager.class);
        if (nm == null) return;

        Intent open = actionIntent(app, candidate.id, contentAction(candidate.actionStyle));
        PendingIntent contentPi = PendingIntent.getBroadcast(
                app,
                requestCode(candidate.id, "content"),
                open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder b = new NotificationCompat.Builder(app, CHANNEL_ID)
                .setSmallIcon(com.pegasuscorp.orbe.R.drawable.ic_stat_pegase)
                .setContentTitle(candidate.title)
                .setContentText(candidate.body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(candidate.body))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(contentPi)
                .setOnlyAlertOnce(true);

        if ("battery".equals(candidate.actionStyle)) {
            b.addAction(0, "Me le rappeler",
                    actionPi(app, candidate.id, IntentionIds.ACTION_REMIND));
            b.addAction(0, "Ignorer aujourd'hui",
                    actionPi(app, candidate.id, IntentionIds.ACTION_IGNORE_TODAY));
            b.addAction(0, "Plus jamais",
                    actionPi(app, candidate.id, IntentionIds.ACTION_NEVER));
        } else if ("f1".equals(candidate.actionStyle)) {
            b.addAction(0, "En parler",
                    actionPi(app, candidate.id, IntentionIds.ACTION_ACCEPT));
            b.addAction(0, "Plus tard",
                    actionPi(app, candidate.id, IntentionIds.ACTION_SNOOZE));
            b.addAction(0, "Pas pour ce GP",
                    actionPi(app, candidate.id, IntentionIds.ACTION_NEVER));
        } else if ("f1_news".equals(candidate.actionStyle)) {
            b.addAction(0, "En parler",
                    actionPi(app, candidate.id, IntentionIds.ACTION_ACCEPT));
            b.addAction(0, "Plus tard",
                    actionPi(app, candidate.id, IntentionIds.ACTION_SNOOZE));
            b.addAction(0, "Pas intéressé",
                    actionPi(app, candidate.id, IntentionIds.ACTION_NEVER));
        } else if ("f1_live".equals(candidate.actionStyle)) {
            b.addAction(0, "En parler",
                    actionPi(app, candidate.id, IntentionIds.ACTION_ACCEPT));
            b.addAction(0, "Plus tard",
                    actionPi(app, candidate.id, IntentionIds.ACTION_SNOOZE));
            b.addAction(0, "Couper live",
                    actionPi(app, candidate.id, IntentionIds.ACTION_NEVER));
        } else if ("life".equals(candidate.actionStyle)
                || "drive".equals(candidate.actionStyle)
                || "orion".equals(candidate.actionStyle)
                || "calendar".equals(candidate.actionStyle)) {
            b.addAction(0, "Oui",
                    actionPi(app, candidate.id, IntentionIds.ACTION_ACCEPT));
            b.addAction(0, "Plus tard",
                    actionPi(app, candidate.id, IntentionIds.ACTION_SNOOZE));
            b.addAction(0, "Plus jamais",
                    actionPi(app, candidate.id, IntentionIds.ACTION_NEVER));
        } else {
            b.addAction(0, "Oui",
                    actionPi(app, candidate.id, IntentionIds.ACTION_ACCEPT));
            b.addAction(0, "Plus tard",
                    actionPi(app, candidate.id, IntentionIds.ACTION_SNOOZE));
            b.addAction(0, "Plus jamais",
                    actionPi(app, candidate.id, IntentionIds.ACTION_NEVER));
        }

        nm.notify(IntentionPrefs.NOTIFICATION_ID, b.build());
    }

    public static void cancel(Context ctx) {
        if (ctx == null) return;
        NotificationManager nm = ctx.getApplicationContext()
                .getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(IntentionPrefs.NOTIFICATION_ID);
        IntentionPrefs.clearActiveNotification(ctx);
    }

    private static String contentAction(String style) {
        if ("battery".equals(style)) return IntentionIds.ACTION_REMIND;
        return IntentionIds.ACTION_ACCEPT;
    }

    private static PendingIntent actionPi(Context app, String id, String action) {
        return PendingIntent.getBroadcast(
                app,
                requestCode(id, action),
                actionIntent(app, id, action),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static Intent actionIntent(Context app, String id, String action) {
        return new Intent(app, IntentionActionReceiver.class)
                .setAction(IntentionActionReceiver.ACTION)
                .putExtra(EXTRA_INTENTION_ID, id)
                .putExtra(EXTRA_ACTION, action);
    }

    static int requestCode(String id, String action) {
        return (String.valueOf(id) + "|" + String.valueOf(action)).hashCode();
    }

    private static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Suggestions Pégase", NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription("Intentions discrètes (batterie, travail, brief, F1)");
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(ch);
    }
}
