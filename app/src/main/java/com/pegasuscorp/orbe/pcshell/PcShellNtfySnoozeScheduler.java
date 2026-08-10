package com.pegasuscorp.orbe.pcshell;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;

/**
 * Reprogramme une notification ntfy « mises à jour Arch » (+N h).
 */
public final class PcShellNtfySnoozeScheduler {

    private static final String PREFS = "pc_shell_ntfy_snooze";
    private static final String KEY_TOPIC = "topic";
    private static final String KEY_TITLE = "title";
    private static final String KEY_MESSAGE = "message";
    private static final String KEY_ACTIONS = "actions";
    private static final String KEY_TOKEN = "token";
    private static final int REQUEST_CODE = 0x50C5;

    private PcShellNtfySnoozeScheduler() {}

    public static void scheduleFromDeepLink(Context context, Uri data) {
        if (context == null || data == null) return;
        String topic = data.getQueryParameter("topic");
        String title = data.getQueryParameter("title");
        String message = data.getQueryParameter("message");
        if (TextUtils.isEmpty(topic) || TextUtils.isEmpty(title)) return;

        int hours = 4;
        try {
            String h = data.getQueryParameter("hours");
            if (h != null && !h.isEmpty()) {
                hours = Math.max(1, Integer.parseInt(h));
            }
        } catch (NumberFormatException ignored) {
        }

        String actions = data.getQueryParameter("actions");
        String token = data.getQueryParameter("token");
        long at = System.currentTimeMillis() + hours * 3600_000L;
        persistPayload(context, topic, title, message, actions, token);
        scheduleAlarm(context, at);
    }

    static void scheduleAlarm(Context context, long atMillis) {
        Context app = context.getApplicationContext();
        AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent intent = new Intent(app, PcShellNtfySnoozeReceiver.class);
        PendingIntent pi = PendingIntent.getBroadcast(app, REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi);
    }

    static SnoozePayload loadPayload(Context context) {
        Context app = context.getApplicationContext();
        android.content.SharedPreferences p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String topic = p.getString(KEY_TOPIC, "");
        if (TextUtils.isEmpty(topic)) return null;
        return new SnoozePayload(
                topic,
                p.getString(KEY_TITLE, ""),
                p.getString(KEY_MESSAGE, ""),
                p.getString(KEY_ACTIONS, ""),
                p.getString(KEY_TOKEN, ""));
    }

    static void clearPayload(Context context) {
        context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
    }

    private static void persistPayload(Context context, String topic, String title, String message,
            String actions, String token) {
        context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_TOPIC, topic != null ? topic : "")
                .putString(KEY_TITLE, title != null ? title : "")
                .putString(KEY_MESSAGE, message != null ? message : "")
                .putString(KEY_ACTIONS, actions != null ? actions : "")
                .putString(KEY_TOKEN, token != null ? token : "")
                .apply();
    }

    static final class SnoozePayload {
        final String topic;
        final String title;
        final String message;
        final String actionsJson;
        final String token;

        SnoozePayload(String topic, String title, String message, String actionsJson, String token) {
            this.topic = topic;
            this.title = title;
            this.message = message;
            this.actionsJson = actionsJson;
            this.token = token;
        }
    }
}
