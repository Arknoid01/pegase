package com.pegasuscorp.orbe.sms;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Telephony;
import android.telephony.SmsMessage;

import androidx.core.app.NotificationCompat;

import com.pegasuscorp.orbe.R;

/**
 * Exigé par Android pour pouvoir devenir app SMS par défaut.
 *
 * Pendant le ménage, c'est Pégase qui reçoit les SMS entrants : seule l'app par
 * défaut les enregistre, donc on les écrit dans la boîte de réception (sinon ils
 * seraient perdus) et on notifie. Google Messages les retrouvera ensuite.
 */
public class SmsDeliverReceiver extends BroadcastReceiver {

    private static final String CHANNEL = "sms_cleaner_incoming";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Telephony.Sms.Intents.SMS_DELIVER_ACTION.equals(intent.getAction())) return;
        SmsMessage[] parts = Telephony.Sms.Intents.getMessagesFromIntent(intent);
        if (parts == null || parts.length == 0) return;

        StringBuilder body = new StringBuilder();
        for (SmsMessage p : parts) {
            if (p != null && p.getMessageBody() != null) body.append(p.getMessageBody());
        }
        String address = parts[0].getDisplayOriginatingAddress();

        ContentValues v = new ContentValues();
        v.put(Telephony.Sms.ADDRESS, address);
        v.put(Telephony.Sms.BODY, body.toString());
        v.put(Telephony.Sms.DATE, System.currentTimeMillis());
        v.put(Telephony.Sms.DATE_SENT, parts[0].getTimestampMillis());
        v.put(Telephony.Sms.READ, 0);
        v.put(Telephony.Sms.SEEN, 0);
        v.put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX);
        try {
            context.getContentResolver().insert(Telephony.Sms.Inbox.CONTENT_URI, v);
        } catch (Exception ignored) {
        }
        notifyIncoming(context, address, body.toString());
    }

    private static void notifyIncoming(Context ctx, String from, String text) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL,
                    "SMS reçus pendant le ménage", NotificationManager.IMPORTANCE_HIGH));
        }
        try {
            nm.notify((int) (System.currentTimeMillis() & 0x7fffffff),
                    new NotificationCompat.Builder(ctx, CHANNEL)
                            .setSmallIcon(R.drawable.ic_stat_pegase)
                            .setContentTitle("SMS de " + from)
                            .setContentText(text)
                            .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                            .setAutoCancel(true)
                            .build());
        } catch (SecurityException ignored) {
            // POST_NOTIFICATIONS refusée : le SMS est quand même enregistré.
        }
    }
}
