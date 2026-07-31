package com.pegasuscorp.orbe.intentions.location;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Paramètres utilisateur — conduite auto GPS, seuils, rayons.
 */
public final class LocationSituationPrefs {

    private static final String PREFS = "pegase_location_prefs";

    private static final String KEY_AUTO_DRIVE = "auto_drive_enabled";
    private static final String KEY_HIDE_COPILOT = "hide_copilot_auto_drive";
    private static final String KEY_ENTER_KMH = "drive_enter_kmh";
    private static final String KEY_EXIT_KMH = "drive_exit_kmh";
    private static final String KEY_SPEED_AGE_MIN = "speed_max_age_min";
    private static final String KEY_DEFAULT_RADIUS_M = "default_radius_m";

    public static final float DEFAULT_ENTER_KMH = 20f;
    public static final float DEFAULT_EXIT_KMH = 15f;
    public static final int DEFAULT_SPEED_AGE_MIN = 10;
    public static final float DEFAULT_RADIUS_M = 120f;

    private LocationSituationPrefs() {}

    public static boolean isAutoDriveEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_AUTO_DRIVE, true);
    }

    public static void setAutoDriveEnabled(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(KEY_AUTO_DRIVE, on).apply();
    }

    public static boolean hideCopilotOnAutoDrive(Context ctx) {
        return prefs(ctx).getBoolean(KEY_HIDE_COPILOT, true);
    }

    public static void setHideCopilotOnAutoDrive(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(KEY_HIDE_COPILOT, on).apply();
    }

    public static float getDriveEnterKmh(Context ctx) {
        return clampKmh(prefs(ctx).getFloat(KEY_ENTER_KMH, DEFAULT_ENTER_KMH), DEFAULT_ENTER_KMH);
    }

    public static void setDriveEnterKmh(Context ctx, float kmh) {
        prefs(ctx).edit().putFloat(KEY_ENTER_KMH, clampKmh(kmh, DEFAULT_ENTER_KMH)).apply();
    }

    public static float getDriveExitKmh(Context ctx) {
        return clampKmh(prefs(ctx).getFloat(KEY_EXIT_KMH, DEFAULT_EXIT_KMH), DEFAULT_EXIT_KMH);
    }

    public static void setDriveExitKmh(Context ctx, float kmh) {
        prefs(ctx).edit().putFloat(KEY_EXIT_KMH, clampKmh(kmh, DEFAULT_EXIT_KMH)).apply();
    }

    public static int getSpeedMaxAgeMinutes(Context ctx) {
        return clampMin(prefs(ctx).getInt(KEY_SPEED_AGE_MIN, DEFAULT_SPEED_AGE_MIN), 1, 120);
    }

    public static void setSpeedMaxAgeMinutes(Context ctx, int minutes) {
        prefs(ctx).edit().putInt(KEY_SPEED_AGE_MIN, clampMin(minutes, 1, 120)).apply();
    }

    public static long getSpeedMaxAgeMs(Context ctx) {
        return getSpeedMaxAgeMinutes(ctx) * 60_000L;
    }

    public static float getDefaultRadiusM(Context ctx) {
        return clampRadius(prefs(ctx).getFloat(KEY_DEFAULT_RADIUS_M, DEFAULT_RADIUS_M));
    }

    public static void setDefaultRadiusM(Context ctx, float radiusM) {
        prefs(ctx).edit().putFloat(KEY_DEFAULT_RADIUS_M, clampRadius(radiusM)).apply();
    }

    static void clearAll(Context ctx) {
        prefs(ctx).edit().clear().apply();
    }

    private static float clampKmh(float v, float fallback) {
        if (Float.isNaN(v) || v < 5f || v > 200f) return fallback;
        return v;
    }

    private static float clampRadius(float v) {
        if (Float.isNaN(v) || v < 30f) return DEFAULT_RADIUS_M;
        if (v > 2000f) return 2000f;
        return v;
    }

    private static int clampMin(int v, int min, int max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
