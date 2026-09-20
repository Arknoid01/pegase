package com.pegasuscorp.orbe.sync;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;

/**
 * Dossier Google Drive (SAF) pour la sync bloc-notes — pas d'API Drive.
 */
public final class DriveSyncPrefs {

    private static final String PREFS = "drive_sync_prefs";
    private static final String KEY_FOLDER_URI = "folder_tree_uri";

    private DriveSyncPrefs() {}

    public static boolean isConfigured(Context context) {
        return getFolderUri(context) != null;
    }

    public static Uri getFolderUri(Context context) {
        String raw = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_FOLDER_URI, "");
        if (TextUtils.isEmpty(raw)) return null;
        try {
            return Uri.parse(raw);
        } catch (Exception e) {
            return null;
        }
    }

    public static void setFolderUri(Context context, Uri uri) {
        context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_FOLDER_URI, uri != null ? uri.toString() : "")
                .apply();
    }

    public static void clear(Context context) {
        setFolderUri(context, null);
    }

    static void resetForTests(Context context) {
        clear(context);
    }
}
