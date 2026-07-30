package com.pegasuscorp.orbe.intentions;

import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.os.Build;

import com.pegasuscorp.orbe.learning.LearningEngine;

/**
 * Batterie + Wi‑Fi + Bluetooth voiture → évaluation capteurs.
 */
public final class IntentionEventReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        String action = intent.getAction();
        if (action == null) return;
        Context app = context.getApplicationContext();

        if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)
                || BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
            BluetoothDevice device = null;
            try {
                device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            } catch (Exception ignored) {}
            boolean connected = BluetoothDevice.ACTION_ACL_CONNECTED.equals(action);
            if (device != null) {
                LearningEngine.recordBtAcl(app, device, connected);
            }
            if (device != null && CarBluetooth.isCarDevice(app, device)) {
                IntentionPrefs.setCarBtConnected(app, connected);
                IntentionEvaluator.evaluateSensors(app);
            }
            return;
        }

        if (Intent.ACTION_BATTERY_CHANGED.equals(action)
                || Intent.ACTION_POWER_CONNECTED.equals(action)
                || Intent.ACTION_POWER_DISCONNECTED.equals(action)
                || ConnectivityManager.CONNECTIVITY_ACTION.equals(action)
                || "android.net.wifi.STATE_CHANGE".equals(action)
                || "android.net.wifi.WIFI_STATE_CHANGED".equals(action)) {
            if (ConnectivityManager.CONNECTIVITY_ACTION.equals(action)
                    || "android.net.wifi.STATE_CHANGE".equals(action)
                    || "android.net.wifi.WIFI_STATE_CHANGED".equals(action)) {
                String ssid = IntentionEvaluator.readCurrentSsid(app);
                String prev = IntentionPrefs.getLastSeenSsid(app);
                LearningEngine.recordWifi(app, ssid, prev);
            }
            IntentionEvaluator.evaluateSensors(app);
        }
    }

    /** Enregistrement runtime (recommandé pour BATTERY_CHANGED). */
    public static void register(Context ctx) {
        if (ctx == null) return;
        Context app = ctx.getApplicationContext();
        IntentionEventReceiver receiver = Holder.INSTANCE;
        if (Holder.registered) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(Intent.ACTION_POWER_CONNECTED);
        filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        filter.addAction("android.net.wifi.STATE_CHANGE");
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                app.registerReceiver(receiver, filter);
            }
            Holder.registered = true;
        } catch (Exception ignored) {}
    }

    private static final class Holder {
        static final IntentionEventReceiver INSTANCE = new IntentionEventReceiver();
        static boolean registered = false;
    }
}
