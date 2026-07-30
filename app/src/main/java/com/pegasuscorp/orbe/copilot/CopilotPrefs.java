package com.pegasuscorp.orbe.copilot;

import android.content.Context;
import android.content.SharedPreferences;

/** Préférences de l'orbe copilote (toujours visible, position, bulle). */
public final class CopilotPrefs {

    private static final String PREFS = "copilot_prefs";
    private static final String KEY_ALWAYS_ON = "always_on";
    private static final String KEY_ORB_X = "orb_x";
    private static final String KEY_ORB_Y = "orb_y";
    private static final String KEY_BUBBLE_OPEN = "bubble_open";

    private CopilotPrefs() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Copilote toujours visible par-dessus les autres apps (défaut : activé). */
    public static boolean isAlwaysOn(Context ctx) {
        return prefs(ctx).getBoolean(KEY_ALWAYS_ON, true);
    }

    public static void setAlwaysOn(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(KEY_ALWAYS_ON, on).apply();
    }

    public static int getOrbX(Context ctx) {
        return prefs(ctx).getInt(KEY_ORB_X, -1);
    }

    public static int getOrbY(Context ctx) {
        return prefs(ctx).getInt(KEY_ORB_Y, -1);
    }

    public static void setOrbPosition(Context ctx, int x, int y) {
        prefs(ctx).edit().putInt(KEY_ORB_X, x).putInt(KEY_ORB_Y, y).apply();
    }

    public static boolean isBubbleOpen(Context ctx) {
        return prefs(ctx).getBoolean(KEY_BUBBLE_OPEN, false);
    }

    public static void setBubbleOpen(Context ctx, boolean open) {
        prefs(ctx).edit().putBoolean(KEY_BUBBLE_OPEN, open).apply();
    }
}
