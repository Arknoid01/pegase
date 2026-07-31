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
    private static final String KEY_TRANSLATION_OVERLAY = "translation_overlay";
    private static final String KEY_NOTIF_ENABLED = "notif_copilot";
    private static final String KEY_NOTIF_WHITELIST = "notif_whitelist";
    private static final String KEY_ELEMENT_HIGHLIGHT = "element_highlight";
    private static final String KEY_REFLECTION_ENABLED = "reflection_enabled";
    private static final String KEY_SCREEN_MAX_AGE_SEC = "screen_max_age_sec";
    private static final String KEY_SCREEN_MAX_CHARS = "screen_max_chars";

    public static final long DEFAULT_SCREEN_MAX_AGE_MS = 45_000L;
    public static final int DEFAULT_SCREEN_MAX_CHARS = 2_000;

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

    public static boolean isTranslationOverlayEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_TRANSLATION_OVERLAY, true);
    }

    public static void setTranslationOverlayEnabled(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(KEY_TRANSLATION_OVERLAY, on).apply();
    }

    public static boolean isNotificationCopilotEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_NOTIF_ENABLED, false);
    }

    public static void setNotificationCopilotEnabled(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(KEY_NOTIF_ENABLED, on).apply();
    }

    public static Set<String> getNotificationWhitelist(Context ctx) {
        String raw = prefs(ctx).getString(KEY_NOTIF_WHITELIST, "");
        if (TextUtils.isEmpty(raw)) return Collections.emptySet();
        Set<String> out = new HashSet<>();
        for (String part : raw.split(",")) {
            String p = part.trim();
            if (!p.isEmpty()) out.add(p);
        }
        return out;
    }

    public static void setNotificationWhitelist(Context ctx, Set<String> packages) {
        if (packages == null || packages.isEmpty()) {
            prefs(ctx).edit().remove(KEY_NOTIF_WHITELIST).apply();
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String p : packages) {
            if (TextUtils.isEmpty(p)) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(p.trim());
        }
        prefs(ctx).edit().putString(KEY_NOTIF_WHITELIST, sb.toString()).apply();
    }

    public static void addToNotificationWhitelist(Context ctx, String packageName) {
        if (TextUtils.isEmpty(packageName)) return;
        Set<String> set = new HashSet<>(getNotificationWhitelist(ctx));
        set.add(packageName.trim());
        setNotificationWhitelist(ctx, set);
    }

    public static void removeFromNotificationWhitelist(Context ctx, String packageName) {
        if (TextUtils.isEmpty(packageName)) return;
        Set<String> set = new HashSet<>(getNotificationWhitelist(ctx));
        set.remove(packageName.trim());
        setNotificationWhitelist(ctx, set);
    }

    public static boolean isElementHighlightEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_ELEMENT_HIGHLIGHT, false);
    }

    public static void setElementHighlightEnabled(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(KEY_ELEMENT_HIGHLIGHT, on).apply();
    }

    public static boolean isNotificationPackageAllowed(Context ctx, String packageName) {
        if (!isNotificationCopilotEnabled(ctx)) return false;
        if (TextUtils.isEmpty(packageName)) return false;
        return getNotificationWhitelist(ctx).contains(packageName);
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

    public static boolean isReflectionEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_REFLECTION_ENABLED, true);
    }

    public static void setReflectionEnabled(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(KEY_REFLECTION_ENABLED, on).apply();
    }

    public static int getScreenMaxAgeSec(Context ctx) {
        int sec = prefs(ctx).getInt(KEY_SCREEN_MAX_AGE_SEC, (int) (DEFAULT_SCREEN_MAX_AGE_MS / 1000L));
        if (sec < 10) return 10;
        if (sec > 300) return 300;
        return sec;
    }

    public static long getScreenMaxAgeMs(Context ctx) {
        return getScreenMaxAgeSec(ctx) * 1000L;
    }

    public static void setScreenMaxAgeSec(Context ctx, int seconds) {
        int sec = seconds;
        if (sec < 10) sec = 10;
        if (sec > 300) sec = 300;
        prefs(ctx).edit().putInt(KEY_SCREEN_MAX_AGE_SEC, sec).apply();
    }

    public static int getScreenMaxChars(Context ctx) {
        int n = prefs(ctx).getInt(KEY_SCREEN_MAX_CHARS, DEFAULT_SCREEN_MAX_CHARS);
        if (n < 200) return 200;
        if (n > 8000) return 8000;
        return n;
    }

    public static void setScreenMaxChars(Context ctx, int chars) {
        int n = chars;
        if (n < 200) n = 200;
        if (n > 8000) n = 8000;
        prefs(ctx).edit().putInt(KEY_SCREEN_MAX_CHARS, n).apply();
    }
}
