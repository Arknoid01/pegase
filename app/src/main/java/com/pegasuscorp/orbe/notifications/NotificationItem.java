package com.pegasuscorp.orbe.notifications;

/**
 * Représentation lisible d'une notification active.
 */
public final class NotificationItem {

    public final int index;
    public final String key;
    public final String packageName;
    public final String appLabel;
    public final String title;
    public final String text;
    public final long when;
    public final boolean clearable;
    public final boolean openable;

    public NotificationItem(int index, String key, String packageName, String appLabel,
                       String title, String text, long when, boolean clearable, boolean openable) {
        this.index = index;
        this.key = key;
        this.packageName = packageName;
        this.appLabel = appLabel;
        this.title = title;
        this.text = text;
        this.when = when;
        this.clearable = clearable;
        this.openable = openable;
    }

    public String spokenLine() {
        StringBuilder sb = new StringBuilder();
        sb.append(index).append(", ").append(appLabel);
        if (title != null && !title.isEmpty()) {
            sb.append(", ").append(title);
        }
        if (text != null && !text.isEmpty()) {
            sb.append(" — ").append(text);
        }
        return sb.toString();
    }
}
