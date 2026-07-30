package com.pegasuscorp.orbe;

import android.content.Context;

/**
 * Persistance des raccourcis apps autour de l'orbe (9 emplacements).
 */
public final class ShortcutStore {

    public static final int SLOT_COUNT = 9;
    private static final String PREFS = "orbe_shortcuts";
    private static final String KEY_SLOT = "slot_";

    private ShortcutStore() {}

    public static String getPackage(Context context, int slot) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_SLOT + slot, null);
    }

    public static void setPackage(Context context, int slot, String packageName) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_SLOT + slot, packageName)
                .apply();
    }

    public static void clearSlot(Context context, int slot) {
        setPackage(context, slot, null);
    }

    private static final String KEY_DEFAULTS_SEEDED = "defaults_seeded";
    private static final String KEY_SPOTIFY_ORB_MIGRATION = "spotify_orb_v1";

    public static boolean isSpotifyPinned(Context context) {
        for (int i = 0; i < SLOT_COUNT; i++) {
            if ("com.spotify.music".equals(getPackage(context, i))) return true;
        }
        return false;
    }

    /** Place Spotify sur l'orbe si l'app est installée. */
    public static boolean pinSpotify(Context context) {
        if (!isSpotifyInstalled(context)) return false;
        if (isSpotifyPinned(context)) return true;
        if (getPackage(context, 0) == null) {
            setPackage(context, 0, "com.spotify.music");
            return true;
        }
        for (int i = 0; i < SLOT_COUNT; i++) {
            if (getPackage(context, i) == null) {
                setPackage(context, i, "com.spotify.music");
                return true;
            }
        }
        setPackage(context, 0, "com.spotify.music");
        return true;
    }

    public static boolean isSpotifyInstalled(Context context) {
        try {
            context.getPackageManager().getPackageInfo("com.spotify.music", 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Première utilisation : épingler Spotify si aucun raccourci n'est configuré. */
    public static void seedDefaultsIfNeeded(Context context) {
        if (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_DEFAULTS_SEEDED, false)) {
            return;
        }
        boolean any = false;
        for (int i = 0; i < SLOT_COUNT; i++) {
            if (getPackage(context, i) != null) {
                any = true;
                break;
            }
        }
        if (!any) {
            pinSpotify(context);
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_DEFAULTS_SEEDED, true)
                .apply();
    }

    /** Ajoute Spotify au menu de l'orbe une fois pour les installations existantes. */
    public static void migrateSpotifyOrbIfNeeded(Context context) {
        if (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_SPOTIFY_ORB_MIGRATION, false)) {
            return;
        }
        pinSpotify(context);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_SPOTIFY_ORB_MIGRATION, true)
                .apply();
    }
}
