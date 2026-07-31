package com.pegasuscorp.orbe.intentions.location;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.ContextCompat;

/**
 * Écoute passive GPS/réseau pour alimenter {@link LocationSituationTracker}.
 */
public final class LocationSituationBootstrap {

    private static final String TAG = "LocationBootstrap";
    private static final long MIN_TIME_MS = 30_000L;
    private static final float MIN_DISTANCE_M = 25f;

    private static volatile boolean started;
    private static LocationListener listener;

    private LocationSituationBootstrap() {}

    public static void ensureStarted(Context ctx) {
        if (ctx == null || started) return;
        Context app = ctx.getApplicationContext();
        if (!hasLocationPermission(app)) {
            LocationSituationTracker.evaluate(app);
            return;
        }
        LocationManager lm = (LocationManager) app.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) {
            LocationSituationTracker.evaluate(app);
            return;
        }
        synchronized (LocationSituationBootstrap.class) {
            if (started) return;
            listener = new LocationListener() {
                @Override
                public void onLocationChanged(Location location) {
                    if (location == null) return;
                    float speed = location.hasSpeed() ? location.getSpeed() : 0f;
                    LocationSituationTracker.onLocationUpdate(
                            app, location.getLatitude(), location.getLongitude(), speed);
                }

                @Override
                public void onProviderDisabled(String provider) {}

                @Override
                public void onProviderEnabled(String provider) {}
            };
            Handler handler = new Handler(Looper.getMainLooper());
            try {
                if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    lm.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                            MIN_TIME_MS, MIN_DISTANCE_M, listener, handler.getLooper());
                }
                if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,
                            MIN_TIME_MS * 2, MIN_DISTANCE_M * 2, listener, handler.getLooper());
                }
                Location last = null;
                try {
                    last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                    if (last == null) {
                        last = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                    }
                } catch (SecurityException ignored) {}
                if (last != null) {
                    float speed = last.hasSpeed() ? last.getSpeed() : 0f;
                    LocationSituationTracker.onLocationUpdate(
                            app, last.getLatitude(), last.getLongitude(), speed);
                } else {
                    LocationSituationTracker.evaluate(app);
                }
                started = true;
            } catch (SecurityException e) {
                Log.w(TAG, "permission manquante", e);
                LocationSituationTracker.evaluate(app);
            } catch (Exception e) {
                Log.w(TAG, "ensureStarted", e);
                LocationSituationTracker.evaluate(app);
            }
        }
    }

    public static boolean hasLocationPermission(Context ctx) {
        if (ctx == null) return false;
        return ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Tests uniquement. */
    static void resetForTests() {
        started = false;
        listener = null;
    }
}
