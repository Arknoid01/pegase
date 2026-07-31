package com.pegasuscorp.orbe.intentions.location;

import android.content.Context;
import android.util.Log;

import com.pegasuscorp.orbe.FloatingOrbService;
import com.pegasuscorp.orbe.intentions.PegaseModeStore;

/**
 * Vitesse &gt; 20 km/h → mode DRIVE auto ; résolution de zone → contexte lieu.
 */
public final class LocationSituationTracker {

    private static final String TAG = "LocationSituation";

    public static final float DRIVE_ENTER_KMH = 20f;
    public static final float DRIVE_EXIT_KMH = 15f;

    private LocationSituationTracker() {}

    /** Tick capteurs (~90 min) ou après mise à jour GPS. */
    public static void evaluate(Context ctx) {
        if (ctx == null) return;
        Context app = ctx.getApplicationContext();
        try {
            LocationSituationReader.Snapshot snap = LocationSituationReader.read(app);
            applyDriveMode(app, snap);
            applyPlaceContext(app, snap);
        } catch (Exception e) {
            Log.w(TAG, "evaluate", e);
        }
    }

    public static void onLocationUpdate(Context ctx, double lat, double lon, float speedMps) {
        if (ctx == null) return;
        Context app = ctx.getApplicationContext();
        LocationSituationReader.persist(app, lat, lon, speedMps);
        evaluate(app);
    }

    static void applyDriveMode(Context app, LocationSituationReader.Snapshot snap) {
        if (!snap.hasCoords) return;
        float kmh = snap.effectiveSpeedKmh(System.currentTimeMillis());
        if (kmh >= DRIVE_ENTER_KMH) {
            if (!PegaseModeStore.isAutoDriveActive(app)) {
                try {
                    FloatingOrbService.hide(app);
                } catch (Exception ignored) {}
            }
            PegaseModeStore.setModeFromAutoDrive(app, PegaseModeStore.Mode.DRIVE);
        } else if (kmh < DRIVE_EXIT_KMH && PegaseModeStore.isAutoDriveActive(app)) {
            PegaseModeStore.exitAutoDrive(app);
        }
    }

    static void applyPlaceContext(Context app, LocationSituationReader.Snapshot snap) {
        if (!snap.hasCoords) {
            LocationSituationReader.setCurrentPlace(app, null);
            return;
        }
        SavedPlaceStore store = SavedPlaceStore.getInstance(app);
        SavedPlace place = store != null ? store.findAt(snap.lat, snap.lon) : null;
        LocationSituationReader.setCurrentPlace(app, place);
    }
}
