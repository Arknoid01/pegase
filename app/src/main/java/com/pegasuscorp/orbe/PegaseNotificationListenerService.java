package com.pegasuscorp.orbe;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import com.pegasuscorp.orbe.notifications.NotificationItem;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Lit, ouvre et efface les notifications système (accès à activer dans les réglages).
 */
public class PegaseNotificationListenerService extends NotificationListenerService {

    private static volatile PegaseNotificationListenerService instance;

    public static PegaseNotificationListenerService getInstance() {
        return instance;
    }

    public static void requestRebindIfNeeded(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;
        try {
            requestRebind(new android.content.ComponentName(
                    context.getApplicationContext(), PegaseNotificationListenerService.class));
        } catch (Exception ignored) {}
    }

    @Override
    public void onListenerConnected() {
        instance = this;
    }

    @Override
    public void onListenerDisconnected() {
        if (instance == this) instance = null;
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;
        try {
            NotificationItem item = toItem(0, sbn, getPackageManager());
            com.pegasuscorp.orbe.copilot.CopilotNotificationBridge.onPosted(this, sbn, item);
        } catch (Exception ignored) {}
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        // Le snapshot est recalculé à la demande.
    }

    public List<NotificationItem> snapshot(int max) {
        StatusBarNotification[] active;
        try {
            active = getActiveNotifications();
        } catch (SecurityException e) {
            return new ArrayList<>();
        }
        if (active == null || active.length == 0) return new ArrayList<>();

        String self = getPackageName();
        List<StatusBarNotification> filtered = new ArrayList<>();
        for (StatusBarNotification sbn : active) {
            if (sbn == null) continue;
            if (self.equals(sbn.getPackageName())) continue;
            if (isGroupSummary(sbn)) continue;
            filtered.add(sbn);
        }

        filtered.sort(Comparator.comparingLong(StatusBarNotification::getPostTime).reversed());

        List<NotificationItem> out = new ArrayList<>();
        PackageManager pm = getPackageManager();
        int idx = 1;
        for (StatusBarNotification sbn : filtered) {
            if (idx > max) break;
            out.add(toItem(idx++, sbn, pm));
        }
        return out;
    }

    public boolean openByIndex(int index) {
        NotificationItem item = findByIndex(index);
        if (item == null || !item.openable) return false;
        return openKey(item.key);
    }

    public boolean openByQuery(String query) {
        if (query == null || query.trim().isEmpty()) return false;
        String needle = query.trim().toLowerCase(Locale.ROOT);
        for (NotificationItem item : snapshot(20)) {
            if (matchesQuery(item, needle)) {
                if (item.openable && openKey(item.key)) return true;
            }
        }
        return false;
    }

    public boolean dismissByIndex(int index) {
        NotificationItem item = findByIndex(index);
        if (item == null || !item.clearable) return false;
        return dismissKey(item.key);
    }

    public boolean dismissByQuery(String query) {
        if (query == null || query.trim().isEmpty()) return false;
        String needle = query.trim().toLowerCase(Locale.ROOT);
        boolean any = false;
        for (NotificationItem item : snapshot(20)) {
            if (!item.clearable) continue;
            if (matchesQuery(item, needle)) {
                if (dismissKey(item.key)) any = true;
            }
        }
        return any;
    }

    public int dismissAllClearable() {
        int count = 0;
        for (NotificationItem item : snapshot(50)) {
            if (item.clearable && dismissKey(item.key)) count++;
        }
        return count;
    }

    public NotificationItem findByIndex(int index) {
        for (NotificationItem item : snapshot(20)) {
            if (item.index == index) return item;
        }
        return null;
    }

    private boolean openKey(String key) {
        StatusBarNotification sbn = findSbn(key);
        if (sbn == null) return false;
        Notification n = sbn.getNotification();
        if (n == null) return false;
        if (n.contentIntent != null && sendIntent(n.contentIntent)) return true;
        if (n.actions != null) {
            for (Notification.Action action : n.actions) {
                if (action != null && action.actionIntent != null
                        && sendIntent(action.actionIntent)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean dismissKey(String key) {
        StatusBarNotification before = findSbn(key);
        if (before == null) return false;
        if (!before.isClearable()) return false;
        try {
            cancelNotification(key);
        } catch (Exception e) {
            return false;
        }
        return findSbn(key) == null;
    }

    private boolean matchesQuery(NotificationItem item, String needle) {
        if (item.appLabel.toLowerCase(Locale.ROOT).contains(needle)
                || item.packageName.toLowerCase(Locale.ROOT).contains(needle)) {
            return true;
        }
        if (item.title != null && item.title.toLowerCase(Locale.ROOT).contains(needle)) {
            return true;
        }
        return item.text != null && item.text.toLowerCase(Locale.ROOT).contains(needle);
    }

    private StatusBarNotification findSbn(String key) {
        StatusBarNotification[] active;
        try {
            active = getActiveNotifications();
        } catch (SecurityException e) {
            return null;
        }
        if (active == null) return null;
        for (StatusBarNotification sbn : active) {
            if (sbn != null && key.equals(sbn.getKey())) return sbn;
        }
        return null;
    }

    private static boolean sendIntent(PendingIntent pi) {
        try {
            pi.send();
            return true;
        } catch (PendingIntent.CanceledException e) {
            return false;
        }
    }

    private static boolean isGroupSummary(StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        if (n == null) return false;
        return (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0;
    }

    private NotificationItem toItem(int index, StatusBarNotification sbn, PackageManager pm) {
        Notification n = sbn.getNotification();
        CharSequence titleCs = n.extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence textCs = n.extras.getCharSequence(Notification.EXTRA_TEXT);
        if (textCs == null || textCs.length() == 0) {
            textCs = n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
        }
        if (textCs == null || textCs.length() == 0) {
            textCs = n.extras.getCharSequence(Notification.EXTRA_SUB_TEXT);
        }
        if (textCs == null || textCs.length() == 0) {
            CharSequence[] lines = n.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
            if (lines != null && lines.length > 0) {
                StringBuilder sb = new StringBuilder();
                for (CharSequence line : lines) {
                    if (line == null || line.length() == 0) continue;
                    if (sb.length() > 0) sb.append(". ");
                    sb.append(line.toString().trim());
                }
                if (sb.length() > 0) textCs = sb;
            }
        }
        if (textCs == null || textCs.length() == 0) {
            textCs = n.tickerText;
        }
        String title = titleCs != null ? titleCs.toString().trim() : "";
        String text = textCs != null ? textCs.toString().trim() : "";
        String pkg = sbn.getPackageName();
        String label = resolveAppLabel(pm, pkg);
        boolean openable = n.contentIntent != null
                || (n.actions != null && n.actions.length > 0);
        return new NotificationItem(index, sbn.getKey(), pkg, label, title, text,
                sbn.getPostTime(), sbn.isClearable(), openable);
    }

    private static String resolveAppLabel(PackageManager pm, String pkg) {
        try {
            ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
            CharSequence label = pm.getApplicationLabel(info);
            if (label != null) return label.toString();
        } catch (PackageManager.NameNotFoundException ignored) {}
        return pkg;
    }

    public static String formatForSpeech(Context ctx, List<NotificationItem> items) {
        if (items == null || items.isEmpty()) {
            return "Tu n'as aucune notification pour l'instant.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(items.size() == 1
                ? "Tu as une notification."
                : "Tu as " + items.size() + " notifications.");
        int limit = Math.min(items.size(), 6);
        for (int i = 0; i < limit; i++) {
            sb.append(' ').append(items.get(i).spokenLine()).append('.');
        }
        if (items.size() > limit) {
            sb.append(" Et ").append(items.size() - limit).append(" autres.");
        }
        return sb.toString();
    }
}
