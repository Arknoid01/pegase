package com.pegasuscorp.orbe.iface;

import android.content.Context;
import android.text.TextUtils;

import com.pegasuscorp.orbe.bureau.BureauSessionStore;
import com.pegasuscorp.orbe.contextstore.ContextualFileStore;
import com.pegasuscorp.orbe.orion.GeneratedFiles;
import com.pegasuscorp.orbe.orion.OrionProjectStore;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Catalogue de l'onglet Fichiers — séparé de {@code PegaseInterfaceData}.
 * Catégories alignées sur le disque : projets Orion, contextes, bureau, générés.
 */
public final class FilesCatalog {

    /** Cache {@code cache/generated/} — pas les vrais projets. */
    public static final String KIND_GENERATED = "generated";
    /** Ancien tag UI (équivalent {@link #KIND_GENERATED}). */
    public static final String KIND_ORION = KIND_GENERATED;
    public static final String KIND_CONTEXT = "context";
    public static final String KIND_BUREAU = "bureau";
    /** Vrais workspaces {@code files/orion/projects/<name>/}. */
    public static final String KIND_PROJECT = "project";

    public enum Category {
        ALL("Tout"),
        PROJECTS("Projets"),
        CONTEXTS("Contextes"),
        BUREAU("Bureau"),
        GENERATED("Générés");

        public final String label;

        Category(String label) {
            this.label = label;
        }
    }

    public static final class Item {
        public final String name;
        public final File file;
        public final long modified;
        public final String kind;

        public Item(String name, File file, long modified, String kind) {
            this.name = name != null ? name : "";
            this.file = file;
            this.modified = modified;
            this.kind = kind != null ? kind : KIND_GENERATED;
        }
    }

    /** Entrée UI : fichier seul ou pack / projet multi-fichiers. */
    public static final class Entry {
        public final boolean bundle;
        public final String name;
        public final String title;
        public final File fileOrDir;
        public final long modified;
        public final List<Item> children;
        public final String kind;

        public Entry(boolean bundle, String name, String title, File fileOrDir,
                long modified, List<Item> children, String kind) {
            this.bundle = bundle;
            this.name = name != null ? name : "";
            this.title = title != null ? title : this.name;
            this.fileOrDir = fileOrDir;
            this.modified = modified;
            this.children = children != null ? children : new ArrayList<>();
            this.kind = kind != null ? kind : KIND_GENERATED;
        }
    }

    private FilesCatalog() {}

    public static List<Entry> listAll(Context ctx) {
        List<Entry> out = new ArrayList<>();
        out.addAll(listProjects(ctx));
        out.addAll(listContexts(ctx));
        out.addAll(listBureau(ctx));
        out.addAll(listGenerated(ctx));
        return out;
    }

    public static List<Entry> listCategory(Context ctx, Category category) {
        if (category == null || category == Category.ALL) return listAll(ctx);
        switch (category) {
            case PROJECTS:
                return listProjects(ctx);
            case CONTEXTS:
                return listContexts(ctx);
            case BUREAU:
                return listBureau(ctx);
            case GENERATED:
                return listGenerated(ctx);
            default:
                return listAll(ctx);
        }
    }

    public static int countCategory(Context ctx, Category category) {
        return listCategory(ctx, category).size();
    }

    /** {@code files/orion/projects/<name>/}. */
    public static List<Entry> listProjects(Context ctx) {
        List<Entry> out = new ArrayList<>();
        if (ctx == null) return out;
        try {
            OrionProjectStore store = OrionProjectStore.get(ctx);
            String active = store.getActiveProject();
            for (String projectName : store.listProjects()) {
                if (TextUtils.isEmpty(projectName)) continue;
                File dir = store.getProjectDir(projectName);
                if (dir == null || !dir.isDirectory()) continue;
                List<OrionProjectStore.ProjectFile> files = store.getProjectFiles(projectName);
                List<Item> kids = new ArrayList<>();
                long newest = dir.lastModified();
                for (OrionProjectStore.ProjectFile pf : files) {
                    if (pf == null || pf.file == null) continue;
                    kids.add(new Item(pf.name, pf.file, pf.modified, KIND_PROJECT));
                    newest = Math.max(newest, pf.modified);
                }
                String title = projectName;
                if (!TextUtils.isEmpty(active) && active.equalsIgnoreCase(projectName)) {
                    title = projectName + " · actif";
                }
                if (kids.isEmpty()) {
                    title = title + " · vide";
                }
                out.add(new Entry(true, projectName, title, dir, newest, kids, KIND_PROJECT));
            }
            out.sort((a, b) -> Long.compare(b.modified, a.modified));
        } catch (Exception ignored) {
        }
        return out;
    }

    /** {@code files/contexts/*.md}. */
    public static List<Entry> listContexts(Context ctx) {
        List<Entry> out = new ArrayList<>();
        if (ctx == null) return out;
        try {
            ContextualFileStore store = ContextualFileStore.getInstance(ctx);
            List<ContextualFileStore.Meta> metas = store.listContexts();
            metas.sort((a, b) -> Long.compare(b.lastModified, a.lastModified));
            for (ContextualFileStore.Meta m : metas) {
                File f = store.resolveContextFile(m.filename);
                if (f == null || !f.isFile()) continue;
                String title = m.keyword + (m.loaded ? " · chargé" : "");
                Item gf = new Item(m.filename, f, m.lastModified, KIND_CONTEXT);
                out.add(new Entry(false, m.filename, title, f, m.lastModified,
                        Collections.singletonList(gf), KIND_CONTEXT));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** {@code files/bureau/*.md} (+ projects/). */
    public static List<Entry> listBureau(Context ctx) {
        List<Entry> out = new ArrayList<>();
        if (ctx == null) return out;
        try {
            for (File f : BureauSessionStore.listMarkdownFiles(ctx)) {
                String rel = f.getName();
                if (f.getParentFile() != null
                        && "projects".equals(f.getParentFile().getName())) {
                    rel = "projects/" + f.getName();
                }
                Item gf = new Item(rel, f, f.lastModified(), KIND_BUREAU);
                out.add(new Entry(false, rel, rel, f, f.lastModified(),
                        Collections.singletonList(gf), KIND_BUREAU));
            }
            out.sort((a, b) -> Long.compare(b.modified, a.modified));
        } catch (Exception ignored) {
        }
        return out;
    }

    /** {@code cache/generated/} — packs éphémères. */
    public static List<Entry> listGenerated(Context ctx) {
        List<Entry> out = new ArrayList<>();
        if (ctx == null) return out;
        try {
            for (GeneratedFiles.Entry e : GeneratedFiles.listEntries(ctx)) {
                List<Item> kids = new ArrayList<>();
                for (File f : e.children) {
                    kids.add(new Item(f.getName(), f, f.lastModified(), KIND_GENERATED));
                }
                String title = e.bundle
                        ? GeneratedFiles.bundleTitle(e.name)
                        : e.name;
                out.add(new Entry(e.bundle, e.name, title, e.fileOrDir, e.modified, kids,
                        KIND_GENERATED));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static String readFilePreview(File file, int maxChars) {
        if (file == null || !file.isFile()) return "";
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                if (sb.length() + line.length() > maxChars) {
                    sb.append(line, 0, Math.max(0, maxChars - sb.length()));
                    sb.append("\n…");
                    break;
                }
                sb.append(line).append('\n');
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "(lecture impossible)";
        }
    }

    public static String emptyMessage(Category category) {
        if (category == null) category = Category.ALL;
        switch (category) {
            case PROJECTS:
                return "Aucun projet Orion.\n\n"
                        + "Les workspaces créés dans l'onglet Orion apparaîtront ici.";
            case CONTEXTS:
                return "Aucun contexte .md.\n\n"
                        + "Charge ou crée des contextes pour les voir ici.";
            case BUREAU:
                return "Aucune session Bureau.\n\n"
                        + "Les .md du bureau (sessions, projets) apparaîtront ici.";
            case GENERATED:
                return "Aucun fichier généré.\n\n"
                        + "Les packs Orion (cache/generated) apparaîtront ici.";
            default:
                return "Aucun fichier pour l'instant.\n\n"
                        + "Projets Orion, contextes, bureau et générés apparaîtront ici.";
        }
    }
}
