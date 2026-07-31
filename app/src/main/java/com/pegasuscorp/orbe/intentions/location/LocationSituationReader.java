package com.pegasuscorp.orbe.intentions.location;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.pegasuscorp.orbe.chat.ApiKeyStore;

/**
 * Dernière position / vitesse connue + lieu résolu (prefs locales).
 */
public final class LocationSituationReader {

    private static final String PREFS = "pegase_location_situation";
    private static final String KEY_LAT = "lat";
    private static final String KEY_LON = "lon";
    private static final String KEY_SPEED_MPS = "speed_mps";
    private static final String KEY_UPDATED_MS = "updated_ms";
    private static final String KEY_PLACE_LABEL = "place_label";
    private static final String KEY_PLACE_TYPE = "place_type";
    private static final String KEY_PLACE_SEARCH = "place_search";

    /** Vitesse ignorée au-delà de cet âge (défaut via {@link LocationSituationPrefs}). */
    public static final long MAX_SPEED_AGE_MS = 10L * 60L * 1000L;

    private static volatile Snapshot testOverride;

    private LocationSituationReader() {}

    public static final class Snapshot {
        public final double lat;
        public final double lon;
        public final float speedMps;
        public final long updatedMs;
        public final boolean hasCoords;

        public Snapshot(double lat, double lon, float speedMps, long updatedMs, boolean hasCoords) {
            this.lat = lat;
            this.lon = lon;
            this.speedMps = speedMps;
            this.updatedMs = updatedMs;
            this.hasCoords = hasCoords;
        }

        public float speedKmh() {
            return speedMps * 3.6f;
        }

        /** Vitesse effective (0 si coordonnées ou relevé trop ancien). */
        public float effectiveSpeedKmh(Context ctx, long nowMs) {
            if (!hasCoords) return 0f;
            long maxAge = LocationSituationPrefs.getSpeedMaxAgeMs(ctx);
            if (updatedMs <= 0L || nowMs - updatedMs > maxAge) return 0f;
            return speedKmh();
        }
    }

    static void setTestOverride(Snapshot snap) {
        testOverride = snap;
    }

    static void clearTestOverride() {
        testOverride = null;
    }

    public static Snapshot read(Context ctx) {
        if (testOverride != null) return testOverride;
        if (ctx == null) return emptySnapshot();
        SharedPreferences p = prefs(ctx);
        double lat = Double.longBitsToDouble(p.getLong(KEY_LAT, 0L));
        double lon = Double.longBitsToDouble(p.getLong(KEY_LON, 0L));
        float speed = p.getFloat(KEY_SPEED_MPS, 0f);
        long updated = p.getLong(KEY_UPDATED_MS, 0L);
        boolean hasCoords = isValidCoord(lat, lon);
        if (!hasCoords) {
            double[] fallback = parseCoords(ApiKeyStore.getUserCoords(ctx));
            if (fallback != null) {
                lat = fallback[0];
                lon = fallback[1];
                hasCoords = true;
                speed = 0f;
            }
        }
        return new Snapshot(lat, lon, speed, updated, hasCoords);
    }

    public static void persist(Context ctx, double lat, double lon, float speedMps) {
        if (ctx == null || !isValidCoord(lat, lon)) return;
        prefs(ctx).edit()
                .putLong(KEY_LAT, Double.doubleToRawLongBits(lat))
                .putLong(KEY_LON, Double.doubleToRawLongBits(lon))
                .putFloat(KEY_SPEED_MPS, Math.max(0f, speedMps))
                .putLong(KEY_UPDATED_MS, System.currentTimeMillis())
                .apply();
    }

    public static void setCurrentPlace(Context ctx, SavedPlace place) {
        if (ctx == null) return;
        SharedPreferences.Editor ed = prefs(ctx).edit();
        if (place == null) {
            ed.remove(KEY_PLACE_LABEL)
                    .remove(KEY_PLACE_TYPE)
                    .remove(KEY_PLACE_SEARCH);
        } else {
            ed.putString(KEY_PLACE_LABEL, place.situationLine())
                    .putString(KEY_PLACE_TYPE, place.type.name())
                    .putString(KEY_PLACE_SEARCH, place.searchTerm());
        }
        ed.apply();
    }

    public static String getCurrentPlaceLabel(Context ctx) {
        if (ctx == null) return "";
        return prefs(ctx).getString(KEY_PLACE_LABEL, "");
    }

    public static String currentPlaceSearchTerm(Context ctx) {
        if (ctx == null) return "";
        return prefs(ctx).getString(KEY_PLACE_SEARCH, "");
    }

    public static boolean isDrivingSpeed(Context ctx, float enterKmh) {
        Snapshot snap = read(ctx);
        return snap.hasCoords && snap.speedKmh() >= enterKmh;
    }

    /** Distance haversine en mètres. */
    public static float distanceM(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371000d;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2d) * Math.sin(dLat / 2d)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2d) * Math.sin(dLon / 2d);
        double c = 2d * Math.atan2(Math.sqrt(a), Math.sqrt(1d - a));
        return (float) (r * c);
    }

    public static double[] parseCoords(String raw) {
        if (TextUtils.isEmpty(raw)) return null;
        String[] parts = raw.trim().split("[,;\\s]+");
        if (parts.length < 2) return null;
        try {
            double lat = Double.parseDouble(parts[0].trim());
            double lon = Double.parseDouble(parts[1].trim());
            if (!isValidCoord(lat, lon)) return null;
            return new double[]{lat, lon};
        } catch (Exception e) {
            return null;
        }
    }

    private static Snapshot emptySnapshot() {
        return new Snapshot(0d, 0d, 0f, 0L, false);
    }

    private static boolean isValidCoord(double lat, double lon) {
        return Math.abs(lat) <= 90d && Math.abs(lon) <= 180d
                && !(lat == 0d && lon == 0d);
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
