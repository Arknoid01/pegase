package com.pegasuscorp.orbe.skills;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Skills : dossiers {@code <id>/SKILL.md} (frontmatter {@code name} + {@code description}) que
 * Pégase charge à la demande. Seul l'index (nom + description) entre dans le prompt ; le corps
 * et les ressources ne sont lus que quand le modèle les demande.
 *
 * <p>Racines : {@code files/skills} (interne) puis {@code getExternalFilesDir/skills}
 * (déposable par USB / ADB / sync). Un skill interne masque un skill externe de même id.
 */
public final class SkillStore {

    public static final String SKILL_FILE = "SKILL.md";
    public static final int MAX_INDEX_SKILLS = 30;
    public static final int MAX_DESCRIPTION_CHARS = 300;
    public static final int MAX_READ_CHARS = 12_000;
    private static final int MAX_ZIP_ENTRIES = 200;
    private static final long MAX_ZIP_BYTES = 5L * 1024 * 1024;

    public static final class Skill {
        public final String id;
        public final String name;
        public final String description;
        public final File dir;

        Skill(String id, String name, String description, File dir) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.dir = dir;
        }
    }

    private final List<File> roots;

    public SkillStore(Context ctx) {
        this(defaultRoots(ctx));
    }

    /** Visible tests. */
    public SkillStore(List<File> roots) {
        this.roots = roots;
    }

    private static List<File> defaultRoots(Context ctx) {
        List<File> out = new ArrayList<>();
        out.add(new File(ctx.getFilesDir(), "skills"));
        File ext = ctx.getExternalFilesDir(null);
        if (ext != null) out.add(new File(ext, "skills"));
        return out;
    }

    /** Racine où {@link #importZip} installe les skills. */
    public File installRoot() {
        return roots.get(0);
    }

    public List<Skill> list() {
        List<Skill> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (File root : roots) {
            File[] dirs = root.listFiles(File::isDirectory);
            if (dirs == null) continue;
            java.util.Arrays.sort(dirs);
            for (File dir : dirs) {
                String id = dir.getName().toLowerCase(Locale.ROOT);
                if (!isValidId(id) || !seen.add(id)) continue;
                Skill skill = parse(id, dir);
                if (skill != null) out.add(skill);
            }
        }
        Collections.sort(out, (a, b) -> a.id.compareTo(b.id));
        return out;
    }

    public Skill find(String id) {
        if (id == null) return null;
        String want = id.trim().toLowerCase(Locale.ROOT);
        for (Skill s : list()) if (s.id.equals(want)) return s;
        return null;
    }

    /** Corps du SKILL.md (sans frontmatter), tronqué. */
    public String readBody(Skill skill) throws IOException {
        String raw = readText(new File(skill.dir, SKILL_FILE));
        return clip(stripFrontmatter(raw));
    }

    /** Ressource du skill (références, scripts…) — chemin relatif confiné au dossier du skill. */
    public String readResource(Skill skill, String relPath) throws IOException {
        if (relPath == null || relPath.trim().isEmpty()) {
            throw new IOException("Chemin de ressource manquant.");
        }
        File base = skill.dir.getCanonicalFile();
        File target = new File(base, relPath.trim()).getCanonicalFile();
        if (!target.getPath().startsWith(base.getPath() + File.separator) || !target.isFile()) {
            throw new IOException("Ressource introuvable dans ce skill : " + relPath.trim());
        }
        return clip(readText(target));
    }

    /** Liste des fichiers (relatifs) d'un skill, hors SKILL.md — pour guider le modèle. */
    public List<String> listResources(Skill skill) {
        List<String> out = new ArrayList<>();
        collect(skill.dir, skill.dir, out);
        Collections.sort(out);
        return out;
    }

    private static void collect(File base, File dir, List<String> out) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                collect(base, f, out);
            } else if (!(dir.equals(base) && SKILL_FILE.equals(f.getName()))) {
                out.add(base.toURI().relativize(f.toURI()).getPath());
            }
            if (out.size() >= 100) return;
        }
    }

    /** Bloc pour le prompt système ; vide s'il n'y a aucun skill. */
    public String promptIndex() {
        List<Skill> skills = list();
        if (skills.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n=== SKILLS ===\n");
        sb.append("Procédures spécialisées. Si la demande correspond à l'une d'elles, appelle "
                + "skills(action:read, id) AVANT d'agir puis suis ses consignes.\n");
        int n = 0;
        for (Skill s : skills) {
            if (n++ >= MAX_INDEX_SKILLS) break;
            sb.append("  • ").append(s.id).append(" — ").append(s.description).append("\n");
        }
        return sb.toString();
    }

    /**
     * Installe un ZIP contenant un ou plusieurs skills ({@code <id>/SKILL.md}, ou SKILL.md à la
     * racine → id = {@code fallbackId}). Protégé contre zip-slip et les archives trop grosses.
     *
     * @return ids installés
     */
    public List<String> importZip(InputStream in, String fallbackId) throws IOException {
        File root = installRoot();
        if (!root.exists() && !root.mkdirs()) throw new IOException("Dossier skills inaccessible.");
        File rootCanon = root.getCanonicalFile();
        List<String> installed = new ArrayList<>();
        long total = 0;
        int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (++entries > MAX_ZIP_ENTRIES) throw new IOException("Archive trop grosse.");
                String name = e.getName().replace('\\', '/');
                if (e.isDirectory() || name.startsWith("/") || name.contains("..")) continue;
                String[] parts = name.split("/");
                String id;
                String rel;
                if (parts.length == 1) {
                    id = fallbackId != null ? fallbackId.toLowerCase(Locale.ROOT) : "skill";
                    rel = name;
                } else {
                    id = parts[0].toLowerCase(Locale.ROOT);
                    rel = name.substring(parts[0].length() + 1);
                }
                if (!isValidId(id) || rel.isEmpty()) continue;
                File target = new File(new File(root, id), rel).getCanonicalFile();
                if (!target.getPath().startsWith(rootCanon.getPath() + File.separator)) {
                    throw new IOException("Chemin d'archive refusé : " + name);
                }
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int r;
                while ((r = zip.read(chunk)) > 0) {
                    total += r;
                    if (total > MAX_ZIP_BYTES) throw new IOException("Archive trop volumineuse.");
                    buf.write(chunk, 0, r);
                }
                File parent = target.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw new IOException("Écriture impossible : " + name);
                }
                java.nio.file.Files.write(target.toPath(), buf.toByteArray());
                if (!installed.contains(id)) installed.add(id);
            }
        }
        List<String> valid = new ArrayList<>();
        for (String id : installed) {
            if (parse(id, new File(root, id)) != null) valid.add(id);
        }
        return valid;
    }

    /** Id de repli dérivé d'un nom de fichier ({@code Mon Skill.zip} → {@code mon-skill}). */
    public static String idFromFileName(String fileName) {
        String base = fileName != null ? fileName : "skill";
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf(':'));
        if (slash >= 0) base = base.substring(slash + 1);
        if (base.toLowerCase(Locale.ROOT).endsWith(".zip")) base = base.substring(0, base.length() - 4);
        String id = base.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "-")
                .replaceAll("^-+|-+$", "");
        if (id.length() > 64) id = id.substring(0, 64);
        return isValidId(id) ? id : "skill";
    }

    static boolean isValidId(String id) {
        return id != null && id.matches("[a-z0-9][a-z0-9_-]{0,63}");
    }

    private static Skill parse(String id, File dir) {
        File file = new File(dir, SKILL_FILE);
        if (!file.isFile()) return null;
        try {
            String raw = readText(file);
            String name = frontmatterValue(raw, "name");
            String desc = frontmatterValue(raw, "description");
            if (desc == null || desc.isEmpty()) return null;
            if (desc.length() > MAX_DESCRIPTION_CHARS) {
                desc = desc.substring(0, MAX_DESCRIPTION_CHARS - 1).trim() + "…";
            }
            return new Skill(id, name != null && !name.isEmpty() ? name : id, desc, dir);
        } catch (IOException e) {
            return null;
        }
    }

    static String frontmatterValue(String raw, String key) {
        String fm = frontmatter(raw);
        if (fm == null) return null;
        for (String line : fm.split("\n")) {
            int c = line.indexOf(':');
            if (c <= 0) continue;
            if (line.substring(0, c).trim().equalsIgnoreCase(key)) {
                String v = line.substring(c + 1).trim();
                if (v.length() >= 2 && (v.startsWith("\"") && v.endsWith("\"")
                        || v.startsWith("'") && v.endsWith("'"))) {
                    v = v.substring(1, v.length() - 1);
                }
                return v;
            }
        }
        return null;
    }

    private static String frontmatter(String raw) {
        String t = raw.replace("\r\n", "\n");
        if (!t.startsWith("---\n")) return null;
        int end = t.indexOf("\n---", 4);
        return end < 0 ? null : t.substring(4, end);
    }

    static String stripFrontmatter(String raw) {
        String t = raw.replace("\r\n", "\n");
        if (!t.startsWith("---\n")) return t.trim();
        int end = t.indexOf("\n---", 4);
        if (end < 0) return t.trim();
        int after = t.indexOf('\n', end + 1);
        return (after < 0 ? "" : t.substring(after + 1)).trim();
    }

    private static String clip(String s) {
        if (s.length() <= MAX_READ_CHARS) return s;
        return s.substring(0, MAX_READ_CHARS) + "\n[… tronqué]";
    }

    private static String readText(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) {
                out.write(buf, 0, r);
                if (out.size() > 256 * 1024) break;
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
