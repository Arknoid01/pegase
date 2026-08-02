package com.pegasuscorp.orbe.voice;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.PowerManager;
import android.util.Log;

/**
 * Anti faux-wake poche : écran éteint + capteur de proximité « near ».
 */
public final class PocketWakeGuard {

    private static final String TAG = "PocketWakeGuard";

    private static final Object LOCK = new Object();
    private static SensorManager sensorManager;
    private static Sensor proximity;
    private static volatile boolean near;
    private static volatile boolean registered;

    private static final SensorEventListener LISTENER = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            if (event == null || event.values == null || event.values.length == 0) return;
            float v = event.values[0];
            float max = event.sensor != null ? event.sensor.getMaximumRange() : 5f;
            // « Near » = valeur basse (souvent 0) quand un objet couvre le capteur.
            near = v < Math.max(0.5f, max * 0.5f);
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    };

    private PocketWakeGuard() {}

    /** Appeler quand le KWS démarre l'écoute. */
    public static void start(Context context) {
        if (context == null) return;
        synchronized (LOCK) {
            if (registered) return;
            Context app = context.getApplicationContext();
            sensorManager = (SensorManager) app.getSystemService(Context.SENSOR_SERVICE);
            if (sensorManager == null) return;
            proximity = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
            if (proximity == null) {
                Log.i(TAG, "no proximity sensor");
                return;
            }
            boolean ok = sensorManager.registerListener(
                    LISTENER, proximity, SensorManager.SENSOR_DELAY_NORMAL);
            registered = ok;
            if (ok) {
                Log.i(TAG, "proximity listening maxRange=" + proximity.getMaximumRange());
            }
        }
    }

    /** Appeler quand le KWS arrête l'écoute. */
    public static void stop() {
        synchronized (LOCK) {
            if (!registered || sensorManager == null) {
                registered = false;
                return;
            }
            try {
                sensorManager.unregisterListener(LISTENER);
            } catch (Exception ignored) {}
            registered = false;
            near = false;
        }
    }

    /**
     * true = ignorer le wake (poche probable).
     * Écran interactif → jamais bloqué (usage normal / main).
     */
    public static boolean shouldSuppressWake(Context context) {
        if (context == null) return false;
        PowerManager pm = (PowerManager) context.getApplicationContext()
                .getSystemService(Context.POWER_SERVICE);
        boolean interactive = pm != null && pm.isInteractive();
        if (interactive) return false;
        return near;
    }

    /** Pour tests / diag. */
    public static boolean isProximityNear() {
        return near;
    }
}
