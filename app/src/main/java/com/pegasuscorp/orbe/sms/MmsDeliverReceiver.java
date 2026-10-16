package com.pegasuscorp.orbe.sms;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Exigé par Android pour devenir app SMS par défaut. Pégase ne gère pas les MMS :
 * un MMS reçu pendant le ménage (quelques secondes) n'est pas téléchargé.
 */
public class MmsDeliverReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
    }
}
