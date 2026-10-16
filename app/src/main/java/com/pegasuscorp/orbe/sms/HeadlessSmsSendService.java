package com.pegasuscorp.orbe.sms;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/** Exigé par Android pour devenir app SMS par défaut (« répondre par SMS » à un appel). Non géré. */
public class HeadlessSmsSendService extends Service {
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        stopSelf(startId);
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
