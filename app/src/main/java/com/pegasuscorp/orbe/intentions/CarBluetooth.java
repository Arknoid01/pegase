package com.pegasuscorp.orbe.intentions;

import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.text.TextUtils;

import java.util.Locale;

/**
 * Heuristiques Bluetooth voiture (nom configuré + classe CAR_AUDIO + mots-clés).
 */
public final class CarBluetooth {

    private CarBluetooth() {}

    public static boolean isCarDevice(Context ctx, BluetoothDevice device) {
        if (device == null) return false;
        String configured = IntentionPrefs.getCarBtName(ctx);
        String name = "";
        try {
            name = device.getName() != null ? device.getName() : "";
        } catch (SecurityException e) {
            return false;
        }
        return isCarName(name, configured) || isCarClass(device);
    }

    public static boolean isCarName(String name, String configured) {
        if (TextUtils.isEmpty(name)) return false;
        String fold = name.toLowerCase(Locale.ROOT);
        if (!TextUtils.isEmpty(configured)) {
            String cfg = configured.toLowerCase(Locale.ROOT).trim();
            if (!cfg.isEmpty() && fold.contains(cfg)) return true;
        }
        return fold.contains("car") || fold.contains("voiture")
                || fold.contains("carkit") || fold.contains("handsfree")
                || fold.contains("bmw") || fold.contains("audi")
                || fold.contains("mercedes") || fold.contains("toyota")
                || fold.contains("peugeot") || fold.contains("renault")
                || fold.contains("citroen") || fold.contains("volkswagen")
                || fold.contains("golf");
    }

    private static boolean isCarClass(BluetoothDevice device) {
        try {
            BluetoothClass cls = device.getBluetoothClass();
            return cls != null
                    && cls.getDeviceClass() == BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO;
        } catch (Exception e) {
            return false;
        }
    }
}
