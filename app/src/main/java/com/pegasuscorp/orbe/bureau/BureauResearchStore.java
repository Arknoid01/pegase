package com.pegasuscorp.orbe.bureau;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Notes de recherche séparées du plan : {@code files/bureau/research/*.md}.
 */
public final class BureauResearchStore {

    private static final String TAG = "BureauResearchStore";

    private BureauResearchStore() {}

    public static File dir(Context ctx) {
        File d = new File(BureauSessionStore.dir(ctx), "research");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static String relativePath(String filename) {
        String name = sanitizeFilename(filename);
        return "research/" + name;
    }

    public static String sanitizeFilename(String name) {
        if (name == null || name.trim().isEmpty()) return "note.md";
        String n = name.trim().toLowerCase(Locale.ROOT)
                .replace('é', 'e').replace('è', 'e').replace('ê', 'e')
                .replace('à', 'a').replace('â', 'a')
                .replace('ù', 'u').replace('û', 'u')
                .replace('ô', 'o').replace('î', 'i').replace('ï', 'i')
                .replace('ç', 'c')
                .replaceAll("[^a-z0-9._-]", "-")
                .replaceAll("-+", "-");
        if (!n.endsWith(".md")) n = n + ".md";
        if (n.contains("..")) n = n.replace("..", "");
        return n;
    }

    public static synchronized boolean save(Context ctx, String filename, String markdown) {
        if (ctx == null) return false;
        String name = sanitizeFilename(filename);
        try {
            File target = new File(dir(ctx), name);
            File tmp = new File(dir(ctx), name + ".tmp");
            byte[] bytes = (markdown == null ? "" : markdown).getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(bytes);
                out.getFD().sync();
            }
            if (target.exists()) {
                //noinspection ResultOfMethodCallIgnored
                target.delete();
            }
            if (!tmp.renameTo(target)) {
                try (FileOutputStream out = new FileOutputStream(target)) {
                    out.write(bytes);
                }
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
            return true;
        } catch (Exception e) {
            Log.w(TAG, "save " + name, e);
            return false;
        }
    }

    public static String load(Context ctx, String filename) {
        if (ctx == null) return null;
        String name = sanitizeFilename(filename);
        File f = new File(dir(ctx), name);
        if (!f.isFile()) return null;
        try {
            byte[] raw = java.nio.file.Files.readAllBytes(f.toPath());
            return new String(raw, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean delete(Context ctx, String filename) {
        if (ctx == null) return false;
        String name = sanitizeFilename(filename);
        File f = new File(dir(ctx), name);
        return f.isFile() && f.delete();
    }

    public static List<String> list(Context ctx) {
        List<String> out = new ArrayList<>();
        File[] files = dir(ctx).listFiles((d, n) ->
                n != null && n.endsWith(".md") && !n.endsWith(".tmp"));
        if (files == null) return out;
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (File f : files) out.add(f.getName());
        return out;
    }

    public static File file(Context ctx, String filename) {
        return new File(dir(ctx), sanitizeFilename(filename));
    }
}
