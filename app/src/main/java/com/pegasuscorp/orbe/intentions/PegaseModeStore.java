package com.pegasuscorp.orbe.intentions;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Mode de session Pégase — NORMAL / WORK / DRIVE.
 */
public final class PegaseModeStore {

    public enum Mode {
        NORMAL,
        WORK,
        DRIVE
    }

    private static final String PREFS = "pegase_mode";
    private static final String KEY_MODE = "mode";
    private static final String KEY_AUTO_DRIVE = "auto_drive";

    private PegaseModeStore() {}

    public static Mode getMode(Context ctx) {
        String raw = prefs(ctx).getString(KEY_MODE, Mode.NORMAL.name());
        try {
            return Mode.valueOf(raw);
        } catch (Exception e) {
            return Mode.NORMAL;
        }
    }

    public static void setMode(Context ctx, Mode mode) {
        Mode m = mode != null ? mode : Mode.NORMAL;
        prefs(ctx).edit()
                .putString(KEY_MODE, m.name())
                .putBoolean(KEY_AUTO_DRIVE, false)
                .apply();
    }

    /** Activation automatique via vitesse GPS — réversible si la vitesse baisse. */
    public static void setModeFromAutoDrive(Context ctx, Mode mode) {
        Mode m = mode != null ? mode : Mode.NORMAL;
        prefs(ctx).edit()
                .putString(KEY_MODE, m.name())
                .putBoolean(KEY_AUTO_DRIVE, true)
                .apply();
    }

    public static boolean isAutoDriveActive(Context ctx) {
        return prefs(ctx).getBoolean(KEY_AUTO_DRIVE, false);
    }

    public static void clearAutoDrive(Context ctx) {
        prefs(ctx).edit().putBoolean(KEY_AUTO_DRIVE, false).apply();
    }

    public static boolean isWork(Context ctx) {
        return getMode(ctx) == Mode.WORK;
    }

    public static boolean isDrive(Context ctx) {
        return getMode(ctx) == Mode.DRIVE;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
