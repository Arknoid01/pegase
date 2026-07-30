package com.pegasuscorp.orbe.notepad;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

/**
 * Planifie les rappels du bloc-notes.
 */
public final class NotepadReminderScheduler {

    private NotepadReminderScheduler() {}

    public static void schedule(Context context, String itemId, String text, long atMillis) {
        if (atMillis <= System.currentTimeMillis()) return;
        Context app = context.getApplicationContext();
        AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent intent = new Intent(app, NotepadReminderReceiver.class)
                .putExtra("item_id", itemId)
                .putExtra("text", text);
        int reqCode = itemId.hashCode();
        PendingIntent pi = PendingIntent.getBroadcast(app, reqCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi);
    }

    public static void cancel(Context context, String itemId) {
        Context app = context.getApplicationContext();
        AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent intent = new Intent(app, NotepadReminderReceiver.class);
        PendingIntent pi = PendingIntent.getBroadcast(app, itemId.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.cancel(pi);
    }
}
