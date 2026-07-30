package com.pegasuscorp.orbe.copilot;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Contexte écran extrait localement (a11y / OCR) — partagé via filesDir + prefs légères.
 * Le cloud ne reçoit que le texte filtré, jamais l'image ni les positions brutes.
 */
public final class ScreenContextStore {

    private static final String PREFS = "copilot_screen_ctx";
    private static final String KEY_TEXT = "last_text";
    private static final String KEY_PACKAGE = "last_package";
    private static final String KEY_TS = "last_ts";

    private ScreenContextStore() {}

    public static void update(Context ctx, String packageName, String plainText) {
        if (ctx == null) return;
        SharedPreferences.Editor ed = prefs(ctx).edit();
        ed.putString(KEY_TEXT, plainText != null ? plainText : "");
        ed.putString(KEY_PACKAGE, packageName != null ? packageName : "");
        ed.putLong(KEY_TS, System.currentTimeMillis());
        ed.apply();
    }

    public static String getLastText(Context ctx) {
        return prefs(ctx).getString(KEY_TEXT, "");
    }

    public static String getLastPackage(Context ctx) {
        return prefs(ctx).getString(KEY_PACKAGE, "");
    }

    public static long getLastTimestampMs(Context ctx) {
        return prefs(ctx).getLong(KEY_TS, 0L);
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
