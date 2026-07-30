package com.pegasuscorp.orbe.copilot;

import android.content.Context;
import android.content.Intent;
import android.service.notification.StatusBarNotification;

import com.pegasuscorp.orbe.FloatingOrbService;
import com.pegasuscorp.orbe.notifications.NotificationItem;

/**
 * Relais notification importante → bulle copilote / broadcast.
 */
public final class CopilotNotificationBridge {

    public static final String ACTION_IMPORTANT_NOTIF =
            "com.pegasuscorp.orbe.copilot.IMPORTANT_NOTIF";

    private CopilotNotificationBridge() {}

    public static void onPosted(Context ctx, StatusBarNotification sbn, NotificationItem item) {
        if (!CopilotNotificationFilter.shouldAlert(ctx, sbn)) return;
        Context app = ctx.getApplicationContext();

        String line = item != null ? item.spokenLine() : "";
        if (line.isEmpty()) return;

        Intent i = new Intent(ACTION_IMPORTANT_NOTIF);
        i.setPackage(app.getPackageName());
        i.putExtra("package", item.packageName);
        i.putExtra("title", item.title);
        i.putExtra("text", item.text);
        i.putExtra("line", line);
        app.sendBroadcast(i);

        // Afficher l'orbe copilote si pas déjà visible
        if (android.provider.Settings.canDrawOverlays(app)
                && CopilotPrefs.isAlwaysOn(app)) {
            FloatingOrbService.showCopilot(app);
        }
    }
}
