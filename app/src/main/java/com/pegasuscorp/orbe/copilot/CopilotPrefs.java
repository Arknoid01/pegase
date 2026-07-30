package com.pegasuscorp.orbe.copilot;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Préférences du mode copilote : orbe, liste blanche d'apps, analyse écran.
 * Liste blanche stricte — aucune analyse hors apps explicitement activées.
 */
public final class CopilotPrefs {

    public static final String PKG_YOUTUBE = "com.google.android.youtube";

    private static final String PREFS = "copilot_prefs";
    private static final String KEY_ALWAYS_ON = "always_on";
    private static final String KEY_ORB_X = "orb_x";
    private static final String KEY_ORB_Y = "orb_y";
    private static final String KEY_BUBBLE_OPEN = "bubble_open";
    private static final String KEY_WHITELIST = "app_whitelist";
    private static final String KEY_ANALYSIS_ENABLED = "screen_analysis";

    private CopilotPrefs() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isAlwaysOn(Context ctx) {
        return prefs(ctx).getBoolean(KEY_ALWAYS_ON, true);
    }

    public static void setAlwaysOn(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(KEY_ALWAYS_ON, on).apply();
    }

    public static boolean isScreenAnalysisEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_ANALYSIS_ENABLED, false);
    }

    public static void setScreenAnalysisEnabled(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(KEY_ANALYSIS_ENABLED, on).apply();
    }

    public static Set<String> getWhitelist(Context ctx) {
        String raw = prefs(ctx).getString(KEY_WHITELIST, "");
        if (TextUtils.isEmpty(raw)) return Collections.emptySet();
        Set<String> out = new HashSet<>();
        for (String part : raw.split(",")) {
            String p = part.trim();
            if (!p.isEmpty()) out.add(p);
        }
        return out;
    }

    public static boolean isPackageAllowed(Context ctx, String packageName) {
        if (TextUtils.isEmpty(packageName)) return false;
        return getWhitelist(ctx).contains(packageName);
    }

    public static void setWhitelist(Context ctx, Set<String> packages) {
        if (packages == null || packages.isEmpty()) {
            prefs(ctx).edit().remove(KEY_WHITELIST).apply();
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String p : packages) {
            if (TextUtils.isEmpty(p)) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(p.trim());
        }
        prefs(ctx).edit().putString(KEY_WHITELIST, sb.toString()).apply();
    }

    public static void addToWhitelist(Context ctx, String packageName) {
        if (TextUtils.isEmpty(packageName)) return;
        Set<String> set = new HashSet<>(getWhitelist(ctx));
        set.add(packageName.trim());
        setWhitelist(ctx, set);
    }

    public static void removeFromWhitelist(Context ctx, String packageName) {
        if (TextUtils.isEmpty(packageName)) return;
        Set<String> set = new HashSet<>(getWhitelist(ctx));
        set.remove(packageName.trim());
        setWhitelist(ctx, set);
    }

    /** Active YouTube dans la liste blanche + analyse (premier cas d'usage). */
    public static void enableYouTubeCopilot(Context ctx) {
        addToWhitelist(ctx, PKG_YOUTUBE);
        setScreenAnalysisEnabled(ctx, true);
    }

    public static boolean isYouTubeEnabled(Context ctx) {
        return getWhitelist(ctx).contains(PKG_YOUTUBE);
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
