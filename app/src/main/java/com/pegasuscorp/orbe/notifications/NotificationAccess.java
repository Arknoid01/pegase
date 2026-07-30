package com.pegasuscorp.orbe.notifications;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.text.TextUtils;

import com.pegasuscorp.orbe.PegaseNotificationListenerService;

/**
 * Vérifie et demande l'accès système « Notification listener ».
 */
public final class NotificationAccess {

    private NotificationAccess() {}

    public static boolean isEnabled(Context context) {
        Context app = context.getApplicationContext();
        String pkg = app.getPackageName();
        String flat = Settings.Secure.getString(
                app.getContentResolver(), "enabled_notification_listeners");
        if (TextUtils.isEmpty(flat)) return false;
        for (String name : flat.split(":")) {
            ComponentName cn = ComponentName.unflattenFromString(name);
            if (cn != null && pkg.equals(cn.getPackageName())) {
                return true;
            }
        }
        return false;
    }

    public static void openSettings(Context context) {
        Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    public static void requestRebind(Context context) {
        PegaseNotificationListenerService.requestRebindIfNeeded(context);
    }

    /** Attend que le service listener soit connecté (appels depuis un thread outil). */
    public static PegaseNotificationListenerService awaitService(Context context, int attempts) {
        Context app = context.getApplicationContext();
        for (int i = 0; i < attempts; i++) {
            PegaseNotificationListenerService svc =
                    PegaseNotificationListenerService.getInstance();
            if (svc != null) return svc;
            requestRebind(app);
            try {
                Thread.sleep(350);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return PegaseNotificationListenerService.getInstance();
    }
}
