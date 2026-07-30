package com.pegasuscorp.orbe.prefetch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Déclenché par {@link PrefetchScheduler} à l'heure du brief matin.
 */
public final class PrefetchAlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null) return;
        try {
            PrefetchService.run(context.getApplicationContext());
        } catch (Exception ignored) {}
    }
}
