package com.pegasuscorp.orbe.orion;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Écrit / partage des fichiers générés (cache/generated — onglet Fichiers).
 */
public final class GeneratedFiles {

    /**
     * Archivage auto dans {@code cache/generated}. Désactivé : bruit (.md, doublons).
     * Remettre à {@code true} pour réactiver sans toucher au projet réel.
     */
    public static boolean AUTO_SAVE_ENABLED = false;

    private static final Pattern FENCE =
            Pattern.compile("```([a-zA-Z0-9_+-]{1,20})\\b");

    /** Bloc ```header\\nbody``` — header peut être lang, lang:path, ou path. */
    private static final Pattern FENCE_BLOCK = Pattern.compile(
            "```([^\\n`]*)\\r?\\n(.*?)```",
            Pattern.DOTALL);

    private static final Pattern FILE_HINT = Pattern.compile(
            "(?i)^\\s*(?:#|//|/\\*|<!--)\\s*(?:file|fichier|path|filename)\\s*[:=]\\s*"
                    + "([\\w./\\\\-]+\\.[\\w]+)");

    private static final Pattern TITLE_FILE = Pattern.compile(
            "(?i)(?:^|\\n)\\s*(?:#{1,3}|\\*\\*)\\s*([\\w./\\\\-]+\\.[a-zA-Z0-9]{1,8})"
                    + "\\s*(?:\\*\\*)?\\s*(?:\\n|$)");

    public static final class Artifact {
        public final String filename;
        public final String content;

        public Artifact(String filename, String content) {
            this.filename = filename != null ? filename : "snippet.txt";
            this.content = content != null ? content : "";
        }
    }

    /** Entrée de l'onglet Fichiers : fichier seul ou pack (dossier). */
    public static final class Entry {
        public final boolean bundle;
        public final String name;
        public final File fileOrDir;
        public final long modified;
        public final List<File> children;

        public Entry(boolean bundle, String name, File fileOrDir, long modified,
                List<File> children) {
            this.bundle = bundle;
            this.name = name != null ? name : "";
            this.fileOrDir = fileOrDir;
            this.modified = modified;
            this.children = children != null ? children : new ArrayList<>();
        }

        public int fileCount() {
            return bundle ? children.size() : 1;
        }
    }

    private GeneratedFiles() {}

    public static File dir(Context ctx) {
        File d = new File(ctx.getCacheDir(), "generated");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** Fichiers générés (récursif, hors .zip), plus récents d'abord. */
    public static List<File> listRecent(Context ctx) {
        List<File> out = new ArrayList<>();
        if (ctx == null) return out;
        collectFiles(dir(ctx), out, true);
        out.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return out;
    }

    /** Liste pour le mini gestionnaire : packs + fichiers à la racine. */
    public static List<Entry> listEntries(Context ctx) {
        List<Entry> out = new ArrayList<>();
        if (ctx == null) return out;
        File root = dir(ctx);
        File[] items = root.listFiles();
        if (items == null) return out;
        Arrays.sort(items, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (File f : items) {
            if (f == null) continue;
            if (f.isDirectory() && f.getName().startsWith("bundle_")) {
                List<File> kids = new ArrayList<>();
                collectFiles(f, kids, true);
                kids.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
                out.add(new Entry(true, f.getName(), f, f.lastModified(), kids));
            } else if (f.isFile() && !f.getName().toLowerCase(Locale.ROOT).endsWith(".zip")) {
                out.add(new Entry(false, f.getName(), f, f.lastModified(),
                        Collections.singletonList(f)));
            }
        }
        return out;
    }

    /** Résout un basename dans {@code cache/generated} (récursif). */
    public static File findByName(Context ctx, String name) {
        if (ctx == null || name == null || name.trim().isEmpty()) return null;
        String base = sanitizeFilename(name);
        File direct = new File(dir(ctx), base);
        if (direct.isFile()) return direct;
        List<File> all = listRecent(ctx);
        for (File f : all) {
            if (f.getName().equals(base) || f.getName().equalsIgnoreCase(base)) return f;
        }
        return null;
    }

    public static String readUtf8(File file) throws Exception {
        if (file == null || !file.isFile()) return "";
        byte[] buf = new byte[(int) Math.min(file.length(), Integer.MAX_VALUE)];
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n < 0) break;
                off += n;
            }
            return new String(buf, 0, off, StandardCharsets.UTF_8);
        }
    }

    /** Extension à partir de fences markdown / indices dans le texte. */
    public static String guessExtension(String content) {
        if (content == null || content.isEmpty()) return ".md";
        Matcher m = FENCE.matcher(content);
        if (m.find()) {
            return extForLang(m.group(1));
        }
        String lower = content.toLowerCase(Locale.ROOT);
        if (lower.contains("package ") && lower.contains("public class")) return ".java";
        if (lower.contains("def ") && lower.contains("import ")) return ".py";
        return ".md";
    }

    public static String defaultOrionName(String content) {
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        return "orion_" + stamp + guessExtension(content);
    }

    /**
     * Extrait les blocs de code de la réponse Orion (fences markdown).
     * Si aucun fence : un seul artefact = réponse entière.
     */
    public static List<Artifact> extractArtifacts(String fullText) {
        List<Artifact> out = new ArrayList<>();
        if (isEmpty(fullText)) return out;

        Matcher m = FENCE_BLOCK.matcher(fullText);
        int index = 0;
        int lastEnd = 0;
        while (m.find()) {
            index++;
            String header = m.group(1) != null ? m.group(1).trim() : "";
            String body = m.group(2) != null ? m.group(2) : "";
            // Trim single trailing newline often present before closing ```
            if (body.endsWith("\n")) body = body.substring(0, body.length() - 1);
            String before = fullText.substring(lastEnd, m.start());
            lastEnd = m.end();

            String name = filenameFromHeader(header);
            if (name == null) name = filenameFromBodyHint(body);
            if (name == null) name = filenameFromTitle(before);
            if (name == null) {
                String ext = extForLang(langFromHeader(header));
                if (".".equals(ext) || ext.isEmpty()) ext = guessExtension(body);
                name = "orion_part" + index + (ext.startsWith(".") ? ext : "." + ext);
            }
            name = sanitizeFilename(name);
            if (!body.trim().isEmpty()) {
                out.add(new Artifact(name, body));
            }
        }

        if (out.isEmpty()) {
            out.add(new Artifact(defaultOrionName(fullText), fullText.trim()));
        }
        return collapseDuplicateNames(out);
    }

    /**
     * Sauve automatiquement la réponse : 1 fichier → racine ;
     * plusieurs → dossier {@code bundle_yyyyMMdd_HHmmss/}.
     * No-op si {@link #AUTO_SAVE_ENABLED} est false.
     * @return fichiers écrits
     */
    public static List<File> autoSaveOrionResponse(Context ctx, String fullText)
            throws Exception {
        List<File> saved = new ArrayList<>();
        if (!AUTO_SAVE_ENABLED) return saved;
        if (ctx == null || isEmpty(fullText)) return saved;
        List<Artifact> arts = extractArtifacts(fullText);
        boolean multi = arts.size() > 1;
        boolean needFull = multi || (arts.size() == 1
                && !arts.get(0).content.trim().equals(fullText.trim()));

        if (!multi) {
            for (Artifact a : arts) {
                saved.add(save(ctx, a.filename, a.content));
            }
            if (needFull) {
                String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                        .format(new Date());
                saved.add(save(ctx, "orion_full_" + stamp + ".md", fullText.trim()));
            }
            return saved;
        }

        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File bundle = new File(dir(ctx), "bundle_" + stamp);
        if (!bundle.exists() && !bundle.mkdirs()) {
            throw new IllegalStateException("Impossible de créer le pack " + bundle.getName());
        }
        for (Artifact a : arts) {
            saved.add(saveInDir(bundle, a.filename, a.content));
        }
        if (needFull) {
            saved.add(saveInDir(bundle, "orion_full.md", fullText.trim()));
        }
        return saved;
    }

    public static File save(Context ctx, String filename, String content) throws Exception {
        if (ctx == null) throw new IllegalArgumentException("ctx");
        return saveInDir(dir(ctx), filename, content);
    }

    public static File saveInDir(File dir, String filename, String content) throws Exception {
        if (dir == null) throw new IllegalArgumentException("dir");
        if (!dir.exists()) dir.mkdirs();
        String name = sanitizeFilename(filename);
        File out = new File(dir, name);
        try (FileOutputStream fos = new FileOutputStream(out)) {
            fos.write((content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
        }
        return out;
    }

    public static File saveOrionOutput(Context ctx, String content) throws Exception {
        return save(ctx, defaultOrionName(content), content);
    }

    /** Crée un ZIP (dans cache/generated) avec les fichiers donnés. */
    public static File zipFiles(Context ctx, List<File> files, String zipBaseName)
            throws Exception {
        if (ctx == null) throw new IllegalArgumentException("ctx");
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("Aucun fichier à zipper");
        }
        String base = zipBaseName == null || zipBaseName.trim().isEmpty()
                ? "orion_pack" : zipBaseName.trim();
        base = base.replaceAll("[^a-zA-Z0-9._\\-]", "_");
        if (!base.toLowerCase(Locale.ROOT).endsWith(".zip")) base = base + ".zip";
        File zip = new File(dir(ctx), base);
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zip))) {
            Set<String> used = new HashSet<>();
            byte[] buf = new byte[8192];
            for (File f : files) {
                if (f == null || !f.isFile()) continue;
                if (f.getName().toLowerCase(Locale.ROOT).endsWith(".zip")) continue;
                String entryName = uniqueZipEntry(f.getName(), used);
                zos.putNextEntry(new ZipEntry(entryName));
                try (FileInputStream in = new FileInputStream(f)) {
                    int n;
                    while ((n = in.read(buf)) >= 0) {
                        zos.write(buf, 0, n);
                    }
                }
                zos.closeEntry();
            }
        }
        return zip;
    }

    public static void share(Activity activity, File file) {
        if (activity == null || file == null || !file.isFile()) {
            if (activity != null) {
                Toast.makeText(activity, "Fichier introuvable", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(activity,
                    activity.getPackageName() + ".fileprovider", file);
            String mime = mimeFor(file.getName());
            Intent share = new Intent(Intent.ACTION_SEND)
                    .setType(mime)
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .putExtra(Intent.EXTRA_SUBJECT, file.getName())
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent chooser = Intent.createChooser(share, "Partager " + file.getName());
            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(chooser);
        } catch (IllegalArgumentException e) {
            Toast.makeText(activity,
                    "Impossible de partager ce fichier (chemin non autorisé).",
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(activity,
                    "Partage impossible : "
                            + (e.getMessage() != null ? e.getMessage() : "erreur"),
                    Toast.LENGTH_LONG).show();
        }
    }

    /** Partage plusieurs fichiers sous forme d'un ZIP. */
    public static void shareAsZip(Activity activity, List<File> files, String zipBaseName) {
        if (activity == null || files == null || files.isEmpty()) return;
        try {
            List<File> toZip = new ArrayList<>();
            for (File f : files) {
                if (f != null && f.isFile()
                        && !f.getName().toLowerCase(Locale.ROOT).endsWith(".zip")) {
                    toZip.add(f);
                }
            }
            if (toZip.isEmpty()) return;
            if (toZip.size() == 1) {
                share(activity, toZip.get(0));
                return;
            }
            String name = zipBaseName;
            if (name == null || name.trim().isEmpty()) {
                String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                        .format(new Date());
                name = "orion_pack_" + stamp;
            }
            File zip = zipFiles(activity, toZip, name);
            share(activity, zip);
        } catch (Exception e) {
            Toast.makeText(activity, "ZIP impossible : "
                            + (e.getMessage() == null ? "erreur" : e.getMessage()),
                    Toast.LENGTH_LONG).show();
        }
    }

    public static boolean deleteFile(File file) {
        return file != null && file.isFile() && file.delete();
    }

    public static boolean deleteRecursive(File fileOrDir) {
        if (fileOrDir == null || !fileOrDir.exists()) return false;
        if (fileOrDir.isDirectory()) {
            File[] kids = fileOrDir.listFiles();
            if (kids != null) {
                for (File k : kids) deleteRecursive(k);
            }
        }
        return fileOrDir.delete();
    }

    /** Efface tout le contenu de cache/generated. */
    public static int clearAll(Context ctx) {
        if (ctx == null) return 0;
        File root = dir(ctx);
        File[] items = root.listFiles();
        if (items == null) return 0;
        int n = 0;
        for (File f : items) {
            if (deleteRecursive(f)) n++;
        }
        return n;
    }

    public static boolean isEmpty(String content) {
        return content == null || content.trim().isEmpty();
    }

    public static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " o";
        if (bytes < 1024 * 1024) return String.format(Locale.FRENCH, "%.1f Ko", bytes / 1024.0);
        return String.format(Locale.FRENCH, "%.1f Mo", bytes / (1024.0 * 1024.0));
    }

    public static String bundleTitle(String folderName) {
        if (folderName == null) return "Pack Orion";
        if (folderName.startsWith("bundle_") && folderName.length() > 7) {
            String stamp = folderName.substring(7);
            return "Pack Orion · " + stamp.replace('_', ' ');
        }
        return folderName;
    }

    private static void collectFiles(File dir, List<File> out, boolean skipZip) {
        if (dir == null || !dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f == null) continue;
            if (f.isDirectory()) {
                collectFiles(f, out, skipZip);
            } else if (f.isFile()) {
                if (skipZip && f.getName().toLowerCase(Locale.ROOT).endsWith(".zip")) continue;
                out.add(f);
            }
        }
    }

    private static String uniqueZipEntry(String name, Set<String> used) {
        String n = sanitizeFilename(name);
        if (used.add(n)) return n;
        int i = 2;
        while (true) {
            String alt = insertBeforeExt(n, "_" + i);
            if (used.add(alt)) return alt;
            i++;
        }
    }

    private static String mimeFor(String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".zip")) return "application/zip";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html";
        if (lower.endsWith(".css")) return "text/css";
        if (lower.endsWith(".js")) return "text/javascript";
        if (lower.endsWith(".xml")) return "text/xml";
        if (lower.endsWith(".md") || lower.endsWith(".txt")
                || lower.endsWith(".java") || lower.endsWith(".kt")
                || lower.endsWith(".py") || lower.endsWith(".sh")) {
            return "text/plain";
        }
        return "application/octet-stream";
    }

    static String filenameFromHeader(String header) {
        if (header == null || header.trim().isEmpty()) return null;
        String h = header.trim();
        // java:Foo.java  or  Foo.java  or  path/Foo.java
        int colon = h.indexOf(':');
        if (colon >= 0 && colon < h.length() - 1) {
            String after = h.substring(colon + 1).trim();
            if (looksLikeFilename(after)) return basename(after);
        }
        // "python foo.py" / "java Main.java"
        String[] parts = h.split("\\s+");
        if (parts.length >= 2 && looksLikeFilename(parts[parts.length - 1])) {
            return basename(parts[parts.length - 1]);
        }
        if (looksLikeFilename(h) && !isLangOnly(h)) return basename(h);
        return null;
    }

    static String filenameFromBodyHint(String body) {
        if (body == null) return null;
        String first = body;
        int nl = body.indexOf('\n');
        if (nl > 0) first = body.substring(0, nl);
        Matcher m = FILE_HINT.matcher(first);
        if (m.find()) return basename(m.group(1));
        return null;
    }

    static String filenameFromTitle(String before) {
        if (before == null || before.isEmpty()) return null;
        String tail = before.length() > 200 ? before.substring(before.length() - 200) : before;
        Matcher m = TITLE_FILE.matcher(tail);
        String last = null;
        while (m.find()) last = m.group(1);
        return last != null ? basename(last) : null;
    }

    static String langFromHeader(String header) {
        if (header == null || header.isEmpty()) return "";
        String h = header.trim();
        int colon = h.indexOf(':');
        if (colon > 0) h = h.substring(0, colon);
        int sp = h.indexOf(' ');
        if (sp > 0) h = h.substring(0, sp);
        return h;
    }

    static String extForLang(String lang) {
        if (lang == null || lang.isEmpty()) return ".md";
        switch (lang.toLowerCase(Locale.ROOT)) {
            case "java":
                return ".java";
            case "kt":
            case "kotlin":
                return ".kt";
            case "py":
            case "python":
                return ".py";
            case "js":
            case "javascript":
                return ".js";
            case "ts":
            case "typescript":
                return ".ts";
            case "tsx":
                return ".tsx";
            case "json":
                return ".json";
            case "xml":
                return ".xml";
            case "html":
                return ".html";
            case "css":
                return ".css";
            case "sh":
            case "bash":
            case "shell":
                return ".sh";
            case "sql":
                return ".sql";
            case "go":
                return ".go";
            case "rs":
            case "rust":
                return ".rs";
            case "c":
                return ".c";
            case "cpp":
            case "c++":
                return ".cpp";
            case "md":
            case "markdown":
                return ".md";
            default:
                return ".txt";
        }
    }

    static String sanitizeFilename(String filename) {
        String name = filename == null ? "document.txt" : filename.trim();
        name = name.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        name = name.replaceAll("[^a-zA-Z0-9._\\-]", "_");
        if (name.isEmpty()) name = "document.txt";
        if (!name.contains(".")) name += ".txt";
        if (name.length() > 80) {
            int dot = name.lastIndexOf('.');
            String ext = dot > 0 ? name.substring(dot) : ".txt";
            name = name.substring(0, Math.min(60, name.length())) + ext;
        }
        return name;
    }

    private static boolean looksLikeFilename(String s) {
        if (s == null) return false;
        String t = s.trim();
        return t.matches("[\\w./\\\\-]+\\.[a-zA-Z0-9]{1,8}");
    }

    private static boolean isLangOnly(String s) {
        String t = s.trim().toLowerCase(Locale.ROOT);
        return t.matches("[a-z0-9_+-]{1,20}") && !t.contains(".");
    }

    private static String basename(String path) {
        String p = path.replace('\\', '/');
        int i = p.lastIndexOf('/');
        return i >= 0 ? p.substring(i + 1) : p;
    }

    /**
     * Une même réponse peut montrer un fichier en plusieurs étapes :
     * on ne garde que la dernière version, sans suffixe {@code _2}, {@code _3}.
     * Ordre des noms distincts = première apparition.
     */
    private static List<Artifact> collapseDuplicateNames(List<Artifact> arts) {
        if (arts == null || arts.isEmpty()) return arts;
        Map<String, Artifact> byName = new LinkedHashMap<>();
        for (Artifact a : arts) {
            if (a == null || isEmpty(a.filename)) continue;
            byName.put(a.filename, a);
        }
        return new ArrayList<>(byName.values());
    }

    private static String insertBeforeExt(String name, String insert) {
        int dot = name.lastIndexOf('.');
        if (dot <= 0) return name + insert;
        return name.substring(0, dot) + insert + name.substring(dot);
    }
}
