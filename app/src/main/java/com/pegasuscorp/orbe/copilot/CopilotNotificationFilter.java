package com.pegasuscorp.orbe.copilot;

import android.app.Notification;
import android.content.Context;
import android.content.Intent;
import android.service.notification.StatusBarNotification;

import com.pegasuscorp.orbe.notifications.NotificationItem;

/**
 * Filtre les notifications pour le copilote — liste blanche stricte, pas tout le flux.
 */
public final class CopilotNotificationFilter {

    private CopilotNotificationFilter() {}

    public static boolean shouldAlert(Context ctx, StatusBarNotification sbn) {
        if (ctx == null || sbn == null) return false;
        if (!CopilotPrefs.isNotificationCopilotEnabled(ctx)) return false;
        String pkg = sbn.getPackageName();
        if (!CopilotPrefs.isNotificationPackageAllowed(ctx, pkg)) return false;
        if (isGroupSummary(sbn)) return false;
        return hasAlertContent(sbn.getNotification());
    }

    /** Contenu titre ou texte non vide — testable sans StatusBarNotification complet. */
    static boolean hasAlertContent(Notification n) {
        if (n == null) return false;
        if (n.priority < Notification.PRIORITY_DEFAULT
                && android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) {
            return false;
        }
        CharSequence title = n.extras != null
                ? n.extras.getCharSequence(Notification.EXTRA_TITLE) : null;
        CharSequence text = n.extras != null
                ? n.extras.getCharSequence(Notification.EXTRA_TEXT) : null;
        return (title != null && title.length() > 0)
                || (text != null && text.length() > 0);
    }

    private static boolean isGroupSummary(StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        if (n == null) return false;
        return (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0;
    }
}
