package com.pegasuscorp.orbe.intentions.location;

import android.content.Context;
import android.util.Log;

import com.pegasuscorp.orbe.FloatingOrbService;
import com.pegasuscorp.orbe.intentions.PegaseModeStore;

/**
 * Vitesse &gt; seuil → mode DRIVE auto ; résolution de zone → contexte lieu.
 */
public final class LocationSituationTracker {

    private static final String TAG = "LocationSituation";

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
        if (!snap.hasCoords || !LocationSituationPrefs.isAutoDriveEnabled(app)) return;
        long now = System.currentTimeMillis();
        float kmh = snap.effectiveSpeedKmh(app, now);
        float enter = LocationSituationPrefs.getDriveEnterKmh(app);
        float exit = LocationSituationPrefs.getDriveExitKmh(app);
        if (exit >= enter) exit = Math.max(5f, enter - 5f);
        if (kmh >= enter) {
            if (!PegaseModeStore.isAutoDriveActive(app)
                    && LocationSituationPrefs.hideCopilotOnAutoDrive(app)) {
                try {
                    FloatingOrbService.hide(app);
                } catch (Exception ignored) {}
            }
            PegaseModeStore.setModeFromAutoDrive(app, PegaseModeStore.Mode.DRIVE);
        } else if (kmh < exit && PegaseModeStore.isAutoDriveActive(app)) {
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
