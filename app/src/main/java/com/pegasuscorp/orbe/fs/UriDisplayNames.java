package com.pegasuscorp.orbe.fs;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

/**
 * Nom lisible d'un {@link Uri} SAF / MediaStore.
 * {@link Uri#getLastPathSegment()} renvoie souvent un ID document ({@code msf:13286}),
 * pas le vrai nom de fichier — préférer {@link OpenableColumns#DISPLAY_NAME}.
 */
public final class UriDisplayNames {

    private UriDisplayNames() {}

    public static String fromUri(Context context, Uri uri) {
        return fromUri(context, uri, "fichier");
    }

    public static String fromUri(Context context, Uri uri, String fallback) {
        String fb = (fallback == null || fallback.isEmpty()) ? "fichier" : fallback;
        if (uri == null) return fb;

        String fromCols = queryOpenableDisplayName(context, uri);
        if (fromCols != null && !fromCols.isEmpty()) {
            return stripPathNoise(fromCols);
        }
        String segment = uri.getLastPathSegment();
        if (segment == null || segment.isEmpty()) return fb;
        return stripPathNoise(segment);
    }

    private static String queryOpenableDisplayName(Context context, Uri uri) {
        if (context == null || uri == null) return null;
        ContentResolver cr = context.getContentResolver();
        try (Cursor c = cr.query(uri, new String[]{OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (c == null || !c.moveToFirst()) return null;
            int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
            if (idx < 0) return null;
            String name = c.getString(idx);
            if (name == null) return null;
            name = name.trim();
            return name.isEmpty() ? null : name;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** {@code primary:Download/foo.md} / {@code msf:13286} → segment après dernier {@code /} ou {@code :}. */
    static String stripPathNoise(String name) {
        if (name == null || name.isEmpty()) return "fichier";
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf(':'));
        if (slash >= 0 && slash < name.length() - 1) {
            return name.substring(slash + 1);
        }
        return name;
    }
}
