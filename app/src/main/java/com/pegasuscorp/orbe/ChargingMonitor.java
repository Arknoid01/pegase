package com.pegasuscorp.orbe;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;

/**
 * Écoute l'état de charge de la batterie.
 */
public final class ChargingMonitor {

    public interface Listener {
        void onChargingChanged(boolean charging);
    }

    private final Context appContext;
    private final Listener listener;
    private boolean registered = false;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            listener.onChargingChanged(isCharging(intent));
        }
    };

    public ChargingMonitor(Context context, Listener listener) {
        this.appContext = context.getApplicationContext();
        this.listener = listener;
    }

    public void start() {
        if (registered) return;
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            appContext.registerReceiver(receiver, filter);
        }
        registered = true;
        Intent sticky = appContext.registerReceiver(null, filter);
        if (sticky != null) {
            listener.onChargingChanged(isCharging(sticky));
        }
    }

    public void stop() {
        if (!registered) return;
        appContext.unregisterReceiver(receiver);
        registered = false;
    }

    public static boolean isCharging(Intent intent) {
        if (intent == null) return false;
        int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        return status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
    }
}
