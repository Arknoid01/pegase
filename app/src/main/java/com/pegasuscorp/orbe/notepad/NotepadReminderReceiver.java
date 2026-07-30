package com.pegasuscorp.orbe.notepad;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import com.pegasuscorp.orbe.R;

/**
 * Notification quand un rappel bloc-notes arrive à échéance.
 */
public class NotepadReminderReceiver extends BroadcastReceiver {

    private static final String CHANNEL_ID = "notepad_reminders";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String text = intent.getStringExtra("text");
        if (text == null || text.isEmpty()) text = "Rappel Pégase";
        ensureChannel(context);
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationCompat.Builder b = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Rappel Pégase")
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true);
        nm.notify((int) (System.currentTimeMillis() & 0x7FFFFFFF), b.build());
    }

    private static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Rappels bloc-notes", NotificationManager.IMPORTANCE_HIGH);
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(ch);
    }
}
