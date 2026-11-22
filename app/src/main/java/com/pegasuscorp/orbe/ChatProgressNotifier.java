package com.pegasuscorp.orbe;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

/**
 * Notification de la discussion texte quand l'utilisateur est parti sur une autre appli :
 * « Pégase réfléchit… » pendant le tour, puis « Pégase a répondu : … » à la fin.
 * Un tap rouvre la discussion ; la notification s'efface dès que la discussion revient
 * au premier plan.
 */
public final class ChatProgressNotifier {

    private static final String CHANNEL_ID = "pegase_chat_progress";
    private static final int NOTIF_ID = 0x9E6A5E;
    private static final int PREVIEW_CHARS = 140;

    private static boolean inFlight;
    private static boolean interfaceForeground;
    private static boolean shown;

    private ChatProgressNotifier() {}

    /** Début d'un tour texte. */
    public static synchronized void onTurnStart(Context ctx) {
        inFlight = true;
        if (!interfaceForeground) showThinking(ctx);
    }

    /** Fin d'un tour texte ; {@code reply} peut être vide (erreur, outil muet). */
    public static synchronized void onTurnEnd(Context ctx, String reply) {
        boolean wasInFlight = inFlight;
        inFlight = false;
        if (interfaceForeground) {
            cancel(ctx);
            return;
        }
        if (wasInFlight || shown) showReplied(ctx, reply);
    }

    public static synchronized void onInterfaceResumed(Context ctx) {
        interfaceForeground = true;
        cancel(ctx);
    }

    public static synchronized void onInterfacePaused(Context ctx) {
        interfaceForeground = false;
        if (inFlight) showThinking(ctx);
    }

    // ---- interne ----

    private static void showThinking(Context ctx) {
        NotificationCompat.Builder b = base(ctx)
                .setContentTitle("Pégase réfléchit…")
                .setContentText("Je te préviens dès que la réponse est prête.")
                .setOngoing(true)
                .setSilent(true)
                .setProgress(0, 0, true)
                // Filet : si le tour est abandonné (discussion fermée), pas de notif bloquée
                .setTimeoutAfter(10L * 60 * 1000);
        post(ctx, b);
    }

    private static void showReplied(Context ctx, String reply) {
        String text = reply != null ? reply.replace('\n', ' ').trim() : "";
        if (text.isEmpty()) text = "La réponse est prête.";
        String preview = text.length() > PREVIEW_CHARS
                ? text.substring(0, PREVIEW_CHARS) + "…" : text;
        NotificationCompat.Builder b = base(ctx)
                .setContentTitle("Pégase a répondu")
                .setContentText(preview)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(preview))
                .setOngoing(false)
                .setAutoCancel(true)
                .setOnlyAlertOnce(false);
        post(ctx, b);
    }

    private static NotificationCompat.Builder base(Context ctx) {
        Context app = ctx.getApplicationContext();
        ensureChannel(app);
        Intent open = new Intent(app, PegaseInterfaceActivity.class)
                .putExtra(PegaseInterfaceState.EXTRA_TAB, PegaseInterfaceState.TAB_CONVERSATION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(app, NOTIF_ID, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(app, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_pegase)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(pi);
    }

    private static void post(Context ctx, NotificationCompat.Builder b) {
        try {
            NotificationManagerCompat.from(ctx.getApplicationContext()).notify(NOTIF_ID, b.build());
            shown = true;
        } catch (SecurityException ignored) {
            // POST_NOTIFICATIONS refusée : on reste silencieux
        }
    }

    private static void cancel(Context ctx) {
        NotificationManagerCompat.from(ctx.getApplicationContext()).cancel(NOTIF_ID);
        shown = false;
    }

    private static void ensureChannel(Context app) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = app.getSystemService(NotificationManager.class);
        if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                "Discussion Pégase", NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription("Réponse en cours / réponse prête quand tu as quitté la discussion");
        nm.createNotificationChannel(ch);
    }
}
