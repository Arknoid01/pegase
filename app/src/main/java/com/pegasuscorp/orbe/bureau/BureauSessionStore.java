package com.pegasuscorp.orbe.bureau;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sessions Markdown du bureau téléphone : {@code files/bureau/session-YYYY-MM-DD.md}.
 * Le canvas tablette garde {@link BureauStore} (JSON) de côté.
 */
public final class BureauSessionStore {

    private static final String TAG = "BureauSessionStore";
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private BureauSessionStore() {}

    public static File dir(Context ctx) {
        File d = new File(ctx.getApplicationContext().getFilesDir(), "bureau");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static String todayFilename() {
        return "session-" + new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()) + ".md";
    }

    public static File todayFile(Context ctx) {
        return new File(dir(ctx), todayFilename());
    }

    public static String defaultHeader() {
        return BureauPlanTemplate.dailyScratch();
    }

    /** Charge la session du jour (crée un en-tête si absente). */
    public static String loadToday(Context ctx) {
        File f = todayFile(ctx);
        if (!f.isFile()) return defaultHeader();
        String raw = readUtf8(f);
        return raw == null || raw.isEmpty() ? defaultHeader() : raw;
    }

    public static String loadFile(Context ctx, String filename) {
        if (filename == null || filename.isEmpty()) return loadToday(ctx);
        // Projets structurés : projects/{slug}.md
        if (BureauProjectStore.isStructuredProjectFile(filename)) {
            String slug = BureauProjectStore.slugFromFilename(filename);
            if (slug != null) {
                String md = BureauProjectStore.loadMarkdown(ctx, slug);
                if (md != null) return md;
            }
        }
        if (filename.contains("..")) return null;
        File f = new File(dir(ctx), filename);
        if (!f.isFile()) return null;
        return readUtf8(f);
    }

    public static void saveTodayAsync(Context ctx, String markdown) {
        saveAsync(ctx, todayFilename(), markdown);
    }

    public static void saveAsync(Context ctx, String filename, String markdown) {
        if (ctx == null || filename == null) return;
        Context app = ctx.getApplicationContext();
        String body = markdown == null ? "" : markdown;
        IO.execute(() -> saveSync(app, filename, body));
    }

    /** Sync (tests / flush). Projets structurés : ne pas écraser la vue générée. */
    public static synchronized void saveSync(Context ctx, String filename, String markdown) {
        if (BureauProjectStore.isStructuredProjectFile(filename)) {
            Log.d(TAG, "skip saveSync for structured project view: " + filename);
            return;
        }
        try {
            if (filename != null && filename.contains("..")) return;
            File target = new File(dir(ctx), filename);
            File parent = target.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            String body = markdown == null ? "" : markdown;
            File tmp = new File(parent != null ? parent : dir(ctx), target.getName() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            if (target.exists()) {
                //noinspection ResultOfMethodCallIgnored
                target.delete();
            }
            if (!tmp.renameTo(target)) {
                try (FileOutputStream out = new FileOutputStream(target)) {
                    out.write(body.getBytes(StandardCharsets.UTF_8));
                }
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        } catch (Exception e) {
            Log.w(TAG, "saveSync " + filename, e);
        }
    }

    public static List<String> listSessionFiles(Context ctx) {
        File[] files = dir(ctx).listFiles((d, name) ->
                name != null && name.startsWith("session-") && name.endsWith(".md"));
        List<String> out = new ArrayList<>();
        if (files == null) return out;
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (File f : files) out.add(f.getName());
        return out;
    }

    /** Tous les .md du bureau (sessions + plans + projets structurés). */
    public static List<File> listMarkdownFiles(Context ctx) {
        List<File> out = new ArrayList<>();
        File[] files = dir(ctx).listFiles((d, name) ->
                name != null && name.endsWith(".md") && !name.endsWith(".tmp"));
        if (files != null) {
            Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            Collections.addAll(out, files);
        }
        File projects = new File(dir(ctx), "projects");
        if (projects.isDirectory()) {
            File[] proj = projects.listFiles((d, name) ->
                    name != null && name.endsWith(".md") && !name.endsWith(".tmp"));
            if (proj != null) {
                Arrays.sort(proj, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                Collections.addAll(out, proj);
            }
        }
        return out;
    }

    /**
     * Supprime un .md bureau et le fil chat associé si présent.
     * @return true si le .md a été effacé
     */
    public static boolean deleteMarkdownFile(Context ctx, String filename) {
        if (ctx == null || filename == null || filename.isEmpty()) return false;
        if (filename.contains("..")) return false;
        String name = filename.replace('\\', '/');
        // Autorise projects/slug.md (projets structurés)
        if (name.contains("/") && !name.startsWith("projects/")) return false;
        if (name.startsWith("projects/")) {
            String leaf = name.substring("projects/".length());
            if (leaf.contains("/") || leaf.isEmpty() || !leaf.endsWith(".md")) return false;
        } else if (name.contains("/") || !name.endsWith(".md")) {
            return false;
        }
        File f = new File(dir(ctx), name);
        if (!f.isFile()) return false;
        boolean ok = f.delete();
        if (ok) {
            try {
                String id = name.replace("projects/", "").replace(".md", "")
                        .replaceAll("[^a-zA-Z0-9._-]", "_");
                if (id.isEmpty()) id = "session-default";
                File chat = new File(dir(ctx), "chat-" + id + ".json");
                if (chat.isFile()) {
                    //noinspection ResultOfMethodCallIgnored
                    chat.delete();
                }
                if (name.startsWith("projects/")) {
                    String slug = name.substring("projects/".length()).replace(".md", "");
                    File json = new File(new File(dir(ctx), "projects"), slug + ".json");
                    if (json.isFile()) {
                        //noinspection ResultOfMethodCallIgnored
                        json.delete();
                    }
                }
            } catch (Exception ignored) {}
        }
        return ok;
    }

    private static String readUtf8(File file) {
        try {
            byte[] raw = java.nio.file.Files.readAllBytes(file.toPath());
            return new String(raw, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}
