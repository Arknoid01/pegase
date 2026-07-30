package com.pegasuscorp.orbe.bureau;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Persistance projets structurés : {@code files/bureau/projects/{slug}.json}
 * + vue Markdown générée {@code files/bureau/projects/{slug}.md}.
 */
public final class BureauProjectStore {

    private static final String TAG = "BureauProjectStore";

    private BureauProjectStore() {}

    public static File dir(Context ctx) {
        File d = new File(BureauSessionStore.dir(ctx), "projects");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static File jsonFile(Context ctx, String slug) {
        return new File(dir(ctx), safeSlug(slug) + ".json");
    }

    public static File mdFile(Context ctx, String slug) {
        return new File(dir(ctx), safeSlug(slug) + ".md");
    }

    /** Nom de fichier relatif bureau pour ouvrir dans le panel : {@code projects/sport.md}. */
    public static String mdFilename(String slug) {
        return "projects/" + safeSlug(slug) + ".md";
    }

    public static boolean exists(Context ctx, String slug) {
        return ctx != null && slug != null && jsonFile(ctx, slug).isFile();
    }

    /** Extrait le slug depuis un filename {@code projects/foo.md} ou {@code foo.md}. */
    public static String slugFromFilename(String filename) {
        if (filename == null) return null;
        String f = filename.replace('\\', '/');
        if (f.startsWith("projects/")) f = f.substring("projects/".length());
        if (f.endsWith(".md")) f = f.substring(0, f.length() - 3);
        if (f.endsWith(".json")) f = f.substring(0, f.length() - 5);
        if (f.isEmpty() || f.contains("/")) return null;
        return f;
    }

    public static boolean isStructuredProjectFile(String filename) {
        if (filename == null) return false;
        String f = filename.replace('\\', '/');
        return f.startsWith("projects/") && f.endsWith(".md");
    }

    public static synchronized BureauProject load(Context ctx, String slug) {
        if (ctx == null || slug == null) return null;
        File f = jsonFile(ctx, slug);
        if (!f.isFile()) return null;
        try {
            byte[] raw = java.nio.file.Files.readAllBytes(f.toPath());
            return fromJson(new JSONObject(new String(raw, StandardCharsets.UTF_8)));
        } catch (Exception e) {
            Log.w(TAG, "load " + slug, e);
            return null;
        }
    }

    public static String loadMarkdown(Context ctx, String slug) {
        if (ctx == null || slug == null) return null;
        File f = mdFile(ctx, slug);
        if (!f.isFile()) return null;
        try {
            byte[] raw = java.nio.file.Files.readAllBytes(f.toPath());
            return new String(raw, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Sauve le JSON et régénère le Markdown.
     * @return true si OK
     */
    public static synchronized boolean save(Context ctx, BureauProject project) {
        if (ctx == null || project == null) return false;
        String slug = project.slug;
        if (slug == null || slug.isEmpty()) {
            slug = BureauProject.slugify(project.title);
            project.slug = slug;
        }
        slug = safeSlug(slug);
        project.slug = slug;
        long now = System.currentTimeMillis();
        if (project.createdAt <= 0) project.createdAt = now;
        project.updatedAt = now;
        if (project.id == null || project.id.isEmpty()) {
            project.id = BureauProject.newId();
        }
        try {
            String json = toJson(project).toString(2);
            writeAtomic(jsonFile(ctx, slug), json);
            String md = BureauMarkdownBuilder.render(project);
            writeAtomic(mdFile(ctx, slug), md);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "save " + slug, e);
            return false;
        }
    }

    public static synchronized boolean delete(Context ctx, String slug) {
        if (ctx == null || slug == null) return false;
        boolean ok = false;
        File j = jsonFile(ctx, slug);
        File m = mdFile(ctx, slug);
        if (j.isFile()) ok = j.delete() || ok;
        if (m.isFile()) ok = m.delete() || ok;
        return ok;
    }

    public static List<String> listSlugs(Context ctx) {
        List<String> out = new ArrayList<>();
        File[] files = dir(ctx).listFiles((d, n) ->
                n != null && n.endsWith(".json") && !n.endsWith(".tmp"));
        if (files == null) return out;
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (File f : files) {
            String name = f.getName();
            out.add(name.substring(0, name.length() - 5));
        }
        return out;
    }

    static String safeSlug(String slug) {
        if (slug == null || slug.isEmpty()) return "projet";
        String s = slug.trim().toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9._-]", "-")
                .replaceAll("-+", "-");
        if (s.isEmpty() || s.contains("..")) return "projet";
        return s;
    }

    static void writeAtomic(File target, String body) throws Exception {
        File dir = target.getParentFile();
        if (dir != null && !dir.exists()) dir.mkdirs();
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        byte[] bytes = (body == null ? "" : body).getBytes(StandardCharsets.UTF_8);
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
    }

    public static JSONObject toJson(BureauProject p) throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", nz(p.id));
        o.put("slug", nz(p.slug));
        o.put("title", nz(p.title));
        o.put("vision", nz(p.vision));
        o.put("createdAt", p.createdAt);
        o.put("updatedAt", p.updatedAt);
        JSONArray objs = new JSONArray();
        for (String s : p.objectives) objs.put(s);
        o.put("objectives", objs);
        JSONArray dec = new JSONArray();
        for (BureauProject.Decision d : p.decisions) {
            JSONObject x = new JSONObject();
            x.put("id", nz(d.id));
            x.put("text", nz(d.text));
            x.put("confidence", d.confidence == null
                    ? BureauProject.Confidence.CONFIRMED.name() : d.confidence.name());
            x.put("reason", nz(d.reason));
            x.put("createdAt", d.createdAt);
            x.put("updatedAt", d.updatedAt);
            dec.put(x);
        }
        o.put("decisions", dec);
        JSONArray tasks = new JSONArray();
        for (BureauProject.Task t : p.tasks) {
            JSONObject x = new JSONObject();
            x.put("id", nz(t.id));
            x.put("text", nz(t.text));
            x.put("done", t.done);
            x.put("createdAt", t.createdAt);
            x.put("updatedAt", t.updatedAt);
            tasks.put(x);
        }
        o.put("tasks", tasks);
        JSONArray qs = new JSONArray();
        for (BureauProject.OpenQuestion q : p.openQuestions) {
            JSONObject x = new JSONObject();
            x.put("id", nz(q.id));
            x.put("text", nz(q.text));
            x.put("createdAt", q.createdAt);
            x.put("updatedAt", q.updatedAt);
            qs.put(x);
        }
        o.put("openQuestions", qs);
        JSONArray refs = new JSONArray();
        for (BureauProject.Reference r : p.references) {
            JSONObject x = new JSONObject();
            x.put("id", nz(r.id));
            x.put("title", nz(r.title));
            x.put("path", nz(r.path));
            x.put("createdAt", r.createdAt);
            x.put("updatedAt", r.updatedAt);
            refs.put(x);
        }
        o.put("references", refs);
        JSONArray hist = new JSONArray();
        for (BureauProject.HistoryEntry h : p.history) {
            JSONObject x = new JSONObject();
            x.put("id", nz(h.id));
            x.put("text", nz(h.text));
            x.put("createdAt", h.createdAt);
            hist.put(x);
        }
        o.put("history", hist);
        return o;
    }

    public static BureauProject fromJson(JSONObject o) {
        BureauProject p = new BureauProject();
        if (o == null) return p;
        p.id = o.optString("id", "");
        p.slug = o.optString("slug", "");
        p.title = o.optString("title", "");
        p.vision = o.optString("vision", "");
        p.createdAt = o.optLong("createdAt", 0);
        p.updatedAt = o.optLong("updatedAt", 0);
        JSONArray objs = o.optJSONArray("objectives");
        if (objs != null) {
            for (int i = 0; i < objs.length(); i++) {
                String s = objs.optString(i, "").trim();
                if (!s.isEmpty()) p.objectives.add(s);
            }
        }
        JSONArray dec = o.optJSONArray("decisions");
        if (dec != null) {
            for (int i = 0; i < dec.length(); i++) {
                JSONObject x = dec.optJSONObject(i);
                if (x == null) continue;
                BureauProject.Decision d = new BureauProject.Decision();
                d.id = x.optString("id", "");
                d.text = x.optString("text", "");
                d.confidence = BureauProject.Confidence.fromString(x.optString("confidence", "CONFIRMED"));
                d.reason = x.optString("reason", "");
                d.createdAt = x.optLong("createdAt", 0);
                d.updatedAt = x.optLong("updatedAt", 0);
                p.decisions.add(d);
            }
        }
        JSONArray tasks = o.optJSONArray("tasks");
        if (tasks != null) {
            for (int i = 0; i < tasks.length(); i++) {
                JSONObject x = tasks.optJSONObject(i);
                if (x == null) continue;
                BureauProject.Task t = new BureauProject.Task();
                t.id = x.optString("id", "");
                t.text = x.optString("text", "");
                t.done = x.optBoolean("done", false);
                t.createdAt = x.optLong("createdAt", 0);
                t.updatedAt = x.optLong("updatedAt", 0);
                p.tasks.add(t);
            }
        }
        JSONArray qs = o.optJSONArray("openQuestions");
        if (qs != null) {
            for (int i = 0; i < qs.length(); i++) {
                JSONObject x = qs.optJSONObject(i);
                if (x == null) continue;
                BureauProject.OpenQuestion q = new BureauProject.OpenQuestion();
                q.id = x.optString("id", "");
                q.text = x.optString("text", "");
                q.createdAt = x.optLong("createdAt", 0);
                q.updatedAt = x.optLong("updatedAt", 0);
                p.openQuestions.add(q);
            }
        }
        JSONArray refs = o.optJSONArray("references");
        if (refs != null) {
            for (int i = 0; i < refs.length(); i++) {
                JSONObject x = refs.optJSONObject(i);
                if (x == null) continue;
                BureauProject.Reference r = new BureauProject.Reference();
                r.id = x.optString("id", "");
                r.title = x.optString("title", "");
                r.path = x.optString("path", "");
                r.createdAt = x.optLong("createdAt", 0);
                r.updatedAt = x.optLong("updatedAt", 0);
                p.references.add(r);
            }
        }
        JSONArray hist = o.optJSONArray("history");
        if (hist != null) {
            for (int i = 0; i < hist.length(); i++) {
                JSONObject x = hist.optJSONObject(i);
                if (x == null) continue;
                BureauProject.HistoryEntry h = new BureauProject.HistoryEntry();
                h.id = x.optString("id", "");
                h.text = x.optString("text", "");
                h.createdAt = x.optLong("createdAt", 0);
                p.history.add(h);
            }
        }
        return p;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
