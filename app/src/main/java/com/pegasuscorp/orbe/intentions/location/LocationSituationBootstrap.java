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
 * Suivi de position pour {@link LocationSituationTracker}.
 *
 * Réseau + passif en permanence (quasi gratuit) ; GPS uniquement quand on semble rouler
 * (cf {@link LocationGpsPolicy}). Avant 2026-11-10 le GPS restait allumé en continu.
 */
public final class LocationSituationBootstrap {

    private static final String TAG = "LocationBootstrap";
    private static final long GPS_MIN_TIME_MS = 30_000L;
    private static final long NET_MIN_TIME_MS = 60_000L;
    private static final float NET_MIN_DISTANCE_M = 50f;
    private static final long PASSIVE_MIN_TIME_MS = 30_000L;
    /** Réévaluation pendant que le GPS est allumé (vitesse périmée à l'arrêt). */
    private static final long GPS_CHECK_MS = 60_000L;

    private static volatile boolean started;
    private static LocationListener listener;
    private static LocationManager lm;
    private static Handler handler;
    private static Context appCtx;
    private static boolean gpsOn;
    private static long lastFastMs;
    private static Location lastFix;

    private static final Runnable GPS_CHECK = new Runnable() {
        @Override
        public void run() {
            updateGps(0f);
            if (gpsOn && handler != null) handler.postDelayed(this, GPS_CHECK_MS);
        }
    };

    private LocationSituationBootstrap() {}

    public static void ensureStarted(Context ctx) {
        if (ctx == null || started) return;
        Context app = ctx.getApplicationContext();
        if (!hasLocationPermission(app)) {
            LocationSituationTracker.evaluate(app);
            return;
        }
        LocationManager mgr = (LocationManager) app.getSystemService(Context.LOCATION_SERVICE);
        if (mgr == null) {
            LocationSituationTracker.evaluate(app);
            return;
        }
        synchronized (LocationSituationBootstrap.class) {
            if (started) return;
            appCtx = app;
            lm = mgr;
            handler = new Handler(Looper.getMainLooper());
            listener = new LocationListener() {
                @Override
                public void onLocationChanged(Location location) {
                    if (location != null) onFix(location);
                }

                @Override
                public void onProviderDisabled(String provider) {
                    updateGps(0f);
                }

                @Override
                public void onProviderEnabled(String provider) {
                    updateGps(0f);
                }
            };
            try {
                if (mgr.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    mgr.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,
                            NET_MIN_TIME_MS, NET_MIN_DISTANCE_M, listener, handler.getLooper());
                }
                // Positions demandées par les autres apps (Maps…) : gratuites.
                mgr.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER,
                        PASSIVE_MIN_TIME_MS, 0f, listener, handler.getLooper());
                Location last = null;
                try {
                    last = mgr.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                    Location net = mgr.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                    if (last == null || (net != null && net.getTime() > last.getTime())) last = net;
                } catch (SecurityException ignored) {}
                started = true;
                if (last != null) {
                    float speed = last.hasSpeed() ? last.getSpeed() : 0f;
                    lastFix = last;
                    LocationSituationTracker.onLocationUpdate(
                            app, last.getLatitude(), last.getLongitude(), speed);
                } else {
                    LocationSituationTracker.evaluate(app);
                }
                updateGps(0f);
            } catch (SecurityException e) {
                Log.w(TAG, "permission manquante", e);
                LocationSituationTracker.evaluate(app);
            } catch (Exception e) {
                Log.w(TAG, "ensureStarted", e);
                LocationSituationTracker.evaluate(app);
            }
        }
    }

    private static void onFix(Location loc) {
        float speed;
        if (loc.hasSpeed()) {
            speed = loc.getSpeed();
        } else if (lastFix != null) {
            float d = LocationSituationReader.distanceM(lastFix.getLatitude(), lastFix.getLongitude(),
                    loc.getLatitude(), loc.getLongitude());
            speed = LocationGpsPolicy.derivedSpeedMps(d, loc.getTime() - lastFix.getTime(),
                    lastFix.hasAccuracy() ? lastFix.getAccuracy() : 100f,
                    loc.hasAccuracy() ? loc.getAccuracy() : 100f);
        } else {
            speed = 0f;
        }
        // Un relevé réseau imprécis ne doit pas écraser une vitesse GPS récente.
        boolean coarse = !loc.hasSpeed() && gpsOn;
        lastFix = loc;
        if (!coarse) {
            LocationSituationTracker.onLocationUpdate(
                    appCtx, loc.getLatitude(), loc.getLongitude(), speed);
        }
        updateGps(speed * 3.6f);
    }

    /** Allume/éteint le GPS selon la vitesse vue et le mode conduite. */
    private static void updateGps(float speedKmh) {
        Context app = appCtx;
        if (app == null || lm == null || listener == null) return;
        long now = System.currentTimeMillis();
        if (speedKmh <= 0f) {
            speedKmh = LocationSituationReader.read(app).effectiveSpeedKmh(app, now);
        }
        if (speedKmh >= LocationSituationPrefs.getDriveExitKmh(app)
                || speedKmh >= LocationGpsPolicy.wakeKmh(LocationSituationPrefs.getDriveEnterKmh(app))) {
            lastFastMs = now;
        }
        boolean network;
        boolean gpsAvail;
        try {
            network = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
            gpsAvail = lm.isProviderEnabled(LocationManager.GPS_PROVIDER);
        } catch (Exception e) {
            return;
        }
        boolean want = gpsAvail && LocationGpsPolicy.shouldGpsBeOn(
                LocationSituationPrefs.isAutoDriveEnabled(app), network,
                com.pegasuscorp.orbe.intentions.PegaseModeStore.isAutoDriveActive(app),
                speedKmh, LocationSituationPrefs.getDriveEnterKmh(app), lastFastMs, now, gpsOn);
        if (want == gpsOn) return;
        try {
            if (want) {
                // Distance 0 : à l'arrêt on reçoit quand même une vitesse nulle → sortie de conduite.
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                        GPS_MIN_TIME_MS, 0f, listener, handler.getLooper());
                gpsOn = true;
                handler.removeCallbacks(GPS_CHECK);
                handler.postDelayed(GPS_CHECK, GPS_CHECK_MS);
                Log.i(TAG, "GPS allumé (" + Math.round(speedKmh) + " km/h)");
            } else {
                // Le passif reste branché sur le même listener : on retire tout puis on le remet.
                lm.removeUpdates(listener);
                gpsOn = false;
                handler.removeCallbacks(GPS_CHECK);
                if (network) {
                    lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,
                            NET_MIN_TIME_MS, NET_MIN_DISTANCE_M, listener, handler.getLooper());
                }
                lm.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER,
                        PASSIVE_MIN_TIME_MS, 0f, listener, handler.getLooper());
                Log.i(TAG, "GPS coupé");
            }
        } catch (SecurityException e) {
            Log.w(TAG, "updateGps", e);
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
        lm = null;
        handler = null;
        appCtx = null;
        gpsOn = false;
        lastFastMs = 0L;
        lastFix = null;
    }
}
