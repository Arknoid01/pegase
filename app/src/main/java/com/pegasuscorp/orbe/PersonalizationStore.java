package com.pegasuscorp.orbe;

import android.content.Context;

/**
 * Préférences de personnalisation (couleurs, icônes, flou).
 */
public final class PersonalizationStore {

    public static final int ICON_SYSTEM = 0;
    public static final int ICON_ROUND = 1;
    public static final int ICON_ROUNDED_SQUARE = 2;

    public static final int BLUR_MAX = 30;

    private static final String PREFS = "orbe_prefs";
    private static final String KEY_COLOR_INDEX = "color_index";
    private static final String KEY_ICON_THEME = "icon_theme";
    private static final String KEY_ICON_PACK = "icon_pack";
    private static final String KEY_HOME_BLUR = "home_blur";
    private static final String KEY_WALLPAPER_MODE = "wallpaper_mode";
    private static final String KEY_WALLPAPER_CUSTOM = "wallpaper_custom";

    public static final int WALLPAPER_SYSTEM = 0;
    public static final int WALLPAPER_CUSTOM = 1;

    private static final String KEY_FLUID_ENABLED = "fluid_enabled";
    private static final String KEY_FLUID_LOCK_WALLPAPER = "fluid_lock_wallpaper";

    private PersonalizationStore() {}

    public static int getColorIndex(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_COLOR_INDEX, 0);
    }

    public static void setColorIndex(Context context, int index) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_COLOR_INDEX, index)
                .apply();
    }

    public static int getIconTheme(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_ICON_THEME, ICON_SYSTEM);
    }

    public static void setIconTheme(Context context, int theme) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_ICON_THEME, theme)
                .apply();
    }

    /** Package du pack d'icônes, ou chaîne vide pour le système. */
    public static String getIconPack(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_ICON_PACK, "");
    }

    public static void setIconPack(Context context, String packageName) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_ICON_PACK, packageName == null ? "" : packageName)
                .apply();
        IconPackManager.clearCache();
    }

    public static int getHomeBlur(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_HOME_BLUR, 0);
    }

    public static void setHomeBlur(Context context, int radius) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_HOME_BLUR, Math.max(0, Math.min(BLUR_MAX, radius)))
                .apply();
    }

    public static int getWallpaperMode(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_WALLPAPER_MODE, WALLPAPER_SYSTEM);
    }

    public static void setWallpaperMode(Context context, int mode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_WALLPAPER_MODE, mode)
                .apply();
    }

    public static String getCustomWallpaperPath(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_WALLPAPER_CUSTOM, "");
    }

    public static void setCustomWallpaperPath(Context context, String path) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_WALLPAPER_CUSTOM, path == null ? "" : path)
                .putInt(KEY_WALLPAPER_MODE, WALLPAPER_CUSTOM)
                .apply();
    }

    public static void clearCustomWallpaper(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_WALLPAPER_CUSTOM)
                .putInt(KEY_WALLPAPER_MODE, WALLPAPER_SYSTEM)
                .apply();
    }

    public static boolean hasCustomWallpaper(Context context) {
        return getWallpaperMode(context) == WALLPAPER_CUSTOM
                && !getCustomWallpaperPath(context).isEmpty();
    }

    /** Fond Fluid animé selon l'heure (accueil). */
    public static boolean isFluidEnabled(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_FLUID_ENABLED, true);
    }

    public static void setFluidEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_FLUID_ENABLED, enabled)
                .apply();
    }

    /** Applique le rendu Fluid sur l'écran de verrouillage système. */
    public static boolean isFluidLockWallpaperEnabled(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_FLUID_LOCK_WALLPAPER, true);
    }

    public static void setFluidLockWallpaperEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_FLUID_LOCK_WALLPAPER, enabled)
                .apply();
    }
}
