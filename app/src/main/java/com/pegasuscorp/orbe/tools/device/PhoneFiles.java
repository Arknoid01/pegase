package com.pegasuscorp.orbe.tools.device;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.TextUtils;
import android.webkit.MimeTypeMap;

import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Accès fichiers téléphone (MediaStore + stockage partagé).
 * Préfère {@link Environment#isExternalStorageManager()} pour search/move/delete complets.
 */
public final class PhoneFiles {

    public static final long IMPORTANT_MOVE_BYTES = 1_000_000L; // 1 Mo

    public static final class Entry {
        public final String name;
        public final String path;
        public final Uri uri;
        public final long size;
        public final String mime;
        public final String relativePath;

        Entry(String name, String path, Uri uri, long size, String mime, String relativePath) {
            this.name = name != null ? name : "";
            this.path = path != null ? path : "";
            this.uri = uri;
            this.size = Math.max(0L, size);
            this.mime = mime != null ? mime : "";
            this.relativePath = relativePath != null ? relativePath : "";
        }

        public boolean isMedia() {
            String m = mime.toLowerCase(Locale.ROOT);
            return m.startsWith("image/") || m.startsWith("video/") || m.startsWith("audio/");
        }

        public String displayLocation() {
            if (!TextUtils.isEmpty(relativePath)) return relativePath;
            if (!TextUtils.isEmpty(path)) return path;
            return uri != null ? uri.toString() : "?";
        }
    }

    private PhoneFiles() {}

    public static boolean hasAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return true;
    }

    public static boolean hasReadPermission(Context ctx) {
        if (hasAllFilesAccess()) return true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(ctx,
                    android.Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
                    || ContextCompat.checkSelfPermission(ctx,
                    android.Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
                    || ContextCompat.checkSelfPermission(ctx,
                    android.Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED;
        }
        return ContextCompat.checkSelfPermission(ctx,
                android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    public static String permissionHint(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !hasAllFilesAccess()) {
            return "Autorise « Accès à tous les fichiers » pour Pégase "
                    + "(Réglages → Applications → Orbe → Autorisations).";
        }
        if (!hasReadPermission(ctx)) {
            return "Autorise l'accès aux photos / fichiers dans les réglages Android.";
        }
        return null;
    }

    /** Ouvre l'écran système « Accès à tous les fichiers » si possible. */
    public static void openManageAllFilesSettings(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            i.setData(Uri.parse("package:" + ctx.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception e) {
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
            } catch (Exception ignored) {
            }
        }
    }

    public static List<Entry> search(Context ctx, String query, int limit) {
        List<Entry> out = new ArrayList<>();
        if (ctx == null || TextUtils.isEmpty(query)) return out;
        String q = query.trim();
        int cap = Math.max(1, Math.min(limit, 25));

        if (hasAllFilesAccess()) {
            scanPublicTrees(q, cap, out);
            if (out.size() >= cap) return out;
        }

        queryMediaStore(ctx, q, cap - out.size(), out);
        return out;
    }

    public static List<Entry> listFolder(Context ctx, String folderKey, int limit) {
        List<Entry> out = new ArrayList<>();
        int cap = Math.max(1, Math.min(limit, 40));
        File dir = resolvePublicDir(folderKey);
        if (dir != null && dir.isDirectory() && hasAllFilesAccess()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (!f.isFile()) continue;
                    out.add(fromFile(f));
                    if (out.size() >= cap) break;
                }
            }
            return out;
        }
        String rel = relativeForFolder(folderKey);
        if (rel != null) {
            queryMediaStoreByPath(ctx, rel, cap, out);
        }
        return out;
    }

    public static Entry resolve(Context ctx, String pathOrName, String query) {
        if (!TextUtils.isEmpty(pathOrName)) {
            File f = new File(pathOrName.trim());
            if (f.isFile()) return fromFile(f);
            List<Entry> byName = search(ctx, pathOrName.trim(), 5);
            if (byName.size() == 1) return byName.get(0);
            if (!byName.isEmpty()) {
                for (Entry e : byName) {
                    if (e.name.equalsIgnoreCase(pathOrName.trim())
                            || e.path.equalsIgnoreCase(pathOrName.trim())) {
                        return e;
                    }
                }
            }
        }
        if (!TextUtils.isEmpty(query)) {
            List<Entry> hits = search(ctx, query.trim(), 5);
            if (hits.size() == 1) return hits.get(0);
            if (!hits.isEmpty()) return hits.get(0);
        }
        return null;
    }

    public static boolean isImportantMove(Entry src, String destFolderKey) {
        if (src == null) return true;
        if (src.size >= IMPORTANT_MOVE_BYTES) return true;
        if (src.isMedia()) return true;
        String dest = normalizeFolder(destFolderKey);
        String loc = (src.relativePath + " " + src.path).toLowerCase(Locale.ROOT);
        if (loc.contains("dcim") || loc.contains("picture") || loc.contains("camera")) {
            return true;
        }
        if ("dcim".equals(dest) || "pictures".equals(dest) || "movies".equals(dest)) {
            return true;
        }
        // Petit fichier texte / doc déjà dans Downloads → Documents : OK sans popup
        if (("downloads".equals(guessFolder(src)) || "documents".equals(guessFolder(src)))
                && ("downloads".equals(dest) || "documents".equals(dest))
                && src.size < IMPORTANT_MOVE_BYTES
                && !src.isMedia()) {
            return false;
        }
        return true;
    }

    public static String move(Context ctx, Entry src, String destFolderKey) throws Exception {
        File destDir = resolvePublicDir(destFolderKey);
        if (destDir == null) {
            throw new IllegalArgumentException(
                    "Dossier cible inconnu. Utilise downloads, documents, pictures ou dcim.");
        }
        if (!destDir.exists() && !destDir.mkdirs()) {
            throw new IllegalStateException("Impossible de créer " + destDir.getAbsolutePath());
        }

        if (!TextUtils.isEmpty(src.path)) {
            File from = new File(src.path);
            if (from.isFile()) {
                File to = uniqueDest(destDir, from.getName());
                if (from.renameTo(to)) {
                    scanFile(ctx, to);
                    return to.getAbsolutePath();
                }
                copyFile(from, to);
                //noinspection ResultOfMethodCallIgnored
                from.delete();
                scanFile(ctx, to);
                return to.getAbsolutePath();
            }
        }

        if (src.uri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            String rel = relativeForFolder(destFolderKey);
            if (rel != null) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, rel);
                int n = ctx.getContentResolver().update(src.uri, values, null, null);
                if (n > 0) {
                    return rel + src.name;
                }
            }
            File to = uniqueDest(destDir, src.name);
            try (InputStream in = ctx.getContentResolver().openInputStream(src.uri);
                 OutputStream out = new FileOutputStream(to)) {
                if (in == null) throw new IllegalStateException("Lecture impossible.");
                byte[] buf = new byte[8192];
                int r;
                while ((r = in.read(buf)) >= 0) out.write(buf, 0, r);
            }
            ctx.getContentResolver().delete(src.uri, null, null);
            scanFile(ctx, to);
            return to.getAbsolutePath();
        }

        throw new IllegalStateException("Déplacement impossible pour ce fichier.");
    }

    /**
     * Corbeille système (API 30+) via PendingIntent, sinon suppression directe.
     * @return message utilisateur
     */
    public static String trashOrDelete(Context ctx, Entry src, boolean permanent) throws Exception {
        if (src == null) throw new IllegalArgumentException("Fichier introuvable.");

        if (!permanent && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && src.uri != null) {
            PendingIntent pi = MediaStore.createTrashRequest(
                    ctx.getContentResolver(),
                    Collections.singletonList(src.uri),
                    true);
            if (ctx instanceof Activity) {
                ((Activity) ctx).startIntentSenderForResult(
                        pi.getIntentSender(), 0, null, 0, 0, 0);
            } else {
                pi.send();
            }
            return "Confirmation Android ouverte — « " + src.name
                    + " » ira dans la corbeille système (30 jours).";
        }

        if (permanent && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && src.uri != null) {
            PendingIntent pi = MediaStore.createDeleteRequest(
                    ctx.getContentResolver(),
                    Collections.singletonList(src.uri));
            if (ctx instanceof Activity) {
                ((Activity) ctx).startIntentSenderForResult(
                        pi.getIntentSender(), 0, null, 0, 0, 0);
            } else {
                pi.send();
            }
            return "Confirmation Android ouverte — suppression définitive de « "
                    + src.name + " ».";
        }

        if (!TextUtils.isEmpty(src.path)) {
            File f = new File(src.path);
            if (f.isFile() && f.delete()) {
                return permanent
                        ? "« " + src.name + " » supprimé."
                        : "« " + src.name + " » supprimé (pas de corbeille système sur ce fichier).";
            }
        }
        if (src.uri != null) {
            int n = ctx.getContentResolver().delete(src.uri, null, null);
            if (n > 0) {
                return "« " + src.name + " » supprimé.";
            }
        }
        throw new IllegalStateException(
                "Suppression refusée — accorde l'accès à tous les fichiers, ou confirme dans Android.");
    }

    public static void open(Context ctx, Entry entry) throws Exception {
        Uri uri = entry.uri;
        if (uri == null && !TextUtils.isEmpty(entry.path)) {
            File f = new File(entry.path);
            uri = FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".fileprovider", f);
        }
        if (uri == null) throw new IllegalStateException("URI manquante.");
        String mime = entry.mime;
        if (TextUtils.isEmpty(mime)) {
            mime = guessMime(entry.name);
        }
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(Intent.createChooser(intent, "Ouvrir " + entry.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    public static String formatList(List<Entry> entries, String header) {
        if (entries == null || entries.isEmpty()) {
            return "Aucun fichier trouvé.";
        }
        StringBuilder sb = new StringBuilder();
        if (!TextUtils.isEmpty(header)) sb.append(header).append('\n');
        int max = Math.min(entries.size(), 12);
        for (int i = 0; i < max; i++) {
            Entry e = entries.get(i);
            sb.append(i + 1).append(". ").append(e.name);
            if (e.size > 0) sb.append(" (").append(formatSize(e.size)).append(')');
            sb.append('\n').append("   ").append(e.displayLocation()).append('\n');
        }
        if (entries.size() > max) {
            sb.append("… et ").append(entries.size() - max).append(" de plus.");
        }
        return sb.toString().trim();
    }

    public static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " o";
        if (bytes < 1024 * 1024) return String.format(Locale.FRANCE, "%.0f Ko", bytes / 1024.0);
        return String.format(Locale.FRANCE, "%.1f Mo", bytes / (1024.0 * 1024.0));
    }

    public static String normalizeFolder(String key) {
        if (key == null) return "downloads";
        String k = key.trim().toLowerCase(Locale.ROOT);
        if (k.contains("download") || k.contains("telecharg") || k.contains("télécharg")) {
            return "downloads";
        }
        if (k.contains("dcim") || k.contains("camera") || k.contains("appareil")) return "dcim";
        if (k.contains("doc")) return "documents";
        if (k.contains("picture") || k.contains("photo") || k.contains("image")) return "pictures";
        if (k.contains("movie") || k.contains("video") || k.contains("film")) return "movies";
        if (k.contains("music") || k.contains("musique") || k.contains("audio")) return "music";
        return k.isEmpty() ? "downloads" : k;
    }

    // ── internals ──────────────────────────────────────────────────────────

    private static void scanPublicTrees(String query, int cap, List<Entry> out) {
        String fold = query.toLowerCase(Locale.ROOT);
        String[] dirs = {
                Environment.DIRECTORY_DOWNLOADS,
                Environment.DIRECTORY_DOCUMENTS,
                Environment.DIRECTORY_DCIM,
                Environment.DIRECTORY_PICTURES,
                Environment.DIRECTORY_MOVIES,
                Environment.DIRECTORY_MUSIC
        };
        for (String d : dirs) {
            File root = Environment.getExternalStoragePublicDirectory(d);
            walkMatch(root, fold, cap, out, 0);
            if (out.size() >= cap) return;
        }
    }

    private static void walkMatch(File dir, String foldQuery, int cap, List<Entry> out, int depth) {
        if (dir == null || !dir.isDirectory() || depth > 4 || out.size() >= cap) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (out.size() >= cap) return;
            if (f.isDirectory()) {
                walkMatch(f, foldQuery, cap, out, depth + 1);
            } else if (f.isFile()) {
                String name = f.getName().toLowerCase(Locale.ROOT);
                String path = f.getAbsolutePath().toLowerCase(Locale.ROOT);
                if (name.contains(foldQuery) || path.contains(foldQuery)) {
                    out.add(fromFile(f));
                }
            }
        }
    }

    private static void queryMediaStore(Context ctx, String query, int limit, List<Entry> out) {
        if (limit <= 0) return;
        Uri collection = MediaStore.Files.getContentUri("external");
        String[] projection = {
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.DATA,
                MediaStore.Files.FileColumns.SIZE,
                MediaStore.Files.FileColumns.MIME_TYPE,
                MediaStore.Files.FileColumns.RELATIVE_PATH
        };
        String selection = MediaStore.Files.FileColumns.DISPLAY_NAME + " LIKE ?";
        String[] args = new String[]{"%" + query + "%"};
        try (Cursor c = ctx.getContentResolver().query(
                collection, projection, selection, args,
                MediaStore.Files.FileColumns.DATE_MODIFIED + " DESC")) {
            if (c == null) return;
            while (c.moveToNext() && out.size() < limit) {
                Entry e = fromCursor(c, collection);
                if (e != null && !containsPath(out, e)) out.add(e);
            }
        } catch (SecurityException ignored) {
        }
    }

    private static void queryMediaStoreByPath(Context ctx, String relativePrefix, int limit,
            List<Entry> out) {
        Uri collection = MediaStore.Files.getContentUri("external");
        String[] projection = {
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.DATA,
                MediaStore.Files.FileColumns.SIZE,
                MediaStore.Files.FileColumns.MIME_TYPE,
                MediaStore.Files.FileColumns.RELATIVE_PATH
        };
        String selection = MediaStore.Files.FileColumns.RELATIVE_PATH + " LIKE ?";
        String[] args = new String[]{relativePrefix + "%"};
        try (Cursor c = ctx.getContentResolver().query(
                collection, projection, selection, args,
                MediaStore.Files.FileColumns.DATE_MODIFIED + " DESC")) {
            if (c == null) return;
            while (c.moveToNext() && out.size() < limit) {
                Entry e = fromCursor(c, collection);
                if (e != null) out.add(e);
            }
        } catch (SecurityException ignored) {
        }
    }

    private static Entry fromCursor(Cursor c, Uri collection) {
        try {
            long id = c.getLong(0);
            String name = c.getString(1);
            String path = c.getString(2);
            long size = c.isNull(3) ? 0L : c.getLong(3);
            String mime = c.getString(4);
            String rel = c.getColumnCount() > 5 ? c.getString(5) : "";
            Uri uri = ContentUris.withAppendedId(collection, id);
            return new Entry(name, path, uri, size, mime, rel);
        } catch (Exception e) {
            return null;
        }
    }

    private static Entry fromFile(File f) {
        String mime = guessMime(f.getName());
        String rel = "";
        try {
            String abs = f.getAbsolutePath();
            String base = Environment.getExternalStorageDirectory().getAbsolutePath();
            if (abs.startsWith(base)) {
                rel = abs.substring(base.length());
                if (rel.startsWith("/")) rel = rel.substring(1);
                int slash = rel.lastIndexOf('/');
                if (slash >= 0) rel = rel.substring(0, slash + 1);
            }
        } catch (Exception ignored) {
        }
        return new Entry(f.getName(), f.getAbsolutePath(), null, f.length(), mime, rel);
    }

    private static boolean containsPath(List<Entry> list, Entry e) {
        for (Entry x : list) {
            if (!TextUtils.isEmpty(e.path) && e.path.equals(x.path)) return true;
            if (e.uri != null && e.uri.equals(x.uri)) return true;
        }
        return false;
    }

    private static File resolvePublicDir(String folderKey) {
        String k = normalizeFolder(folderKey);
        switch (k) {
            case "documents":
                return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
            case "pictures":
                return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
            case "dcim":
                return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
            case "movies":
                return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
            case "music":
                return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
            case "downloads":
            default:
                return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        }
    }

    private static String relativeForFolder(String folderKey) {
        String k = normalizeFolder(folderKey);
        switch (k) {
            case "documents":
                return Environment.DIRECTORY_DOCUMENTS + "/";
            case "pictures":
                return Environment.DIRECTORY_PICTURES + "/";
            case "dcim":
                return Environment.DIRECTORY_DCIM + "/";
            case "movies":
                return Environment.DIRECTORY_MOVIES + "/";
            case "music":
                return Environment.DIRECTORY_MUSIC + "/";
            case "downloads":
            default:
                return Environment.DIRECTORY_DOWNLOADS + "/";
        }
    }

    private static String guessFolder(Entry e) {
        String loc = (e.relativePath + " " + e.path).toLowerCase(Locale.ROOT);
        if (loc.contains("download")) return "downloads";
        if (loc.contains("document")) return "documents";
        if (loc.contains("dcim")) return "dcim";
        if (loc.contains("picture")) return "pictures";
        if (loc.contains("movie")) return "movies";
        if (loc.contains("music")) return "music";
        return "";
    }

    private static File uniqueDest(File dir, String name) {
        File to = new File(dir, name);
        if (!to.exists()) return to;
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        for (int i = 2; i < 100; i++) {
            to = new File(dir, base + "_" + i + ext);
            if (!to.exists()) return to;
        }
        return new File(dir, base + "_" + System.currentTimeMillis() + ext);
    }

    private static void copyFile(File from, File to) throws Exception {
        try (InputStream in = new FileInputStream(from);
             OutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) >= 0) out.write(buf, 0, r);
        }
    }

    private static void scanFile(Context ctx, File file) {
        try {
            Intent intent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
            intent.setData(Uri.fromFile(file));
            ctx.sendBroadcast(intent);
        } catch (Exception ignored) {
        }
    }

    private static String guessMime(String name) {
        if (name == null) return "application/octet-stream";
        int dot = name.lastIndexOf('.');
        if (dot < 0) return "application/octet-stream";
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return mime != null ? mime : "application/octet-stream";
    }
}
