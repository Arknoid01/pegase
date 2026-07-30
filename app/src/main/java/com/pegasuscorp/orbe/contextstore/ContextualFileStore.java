package com.pegasuscorp.orbe.contextstore;

import android.content.Context;
import android.util.Log;

import com.pegasuscorp.orbe.diag.CorrectionsStore;
import com.pegasuscorp.orbe.fs.PegaseFileSystem;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Contextes nommés : fichiers .md locaux chargés à la demande (complément de la mémoire RAG).
 * Stockage : {@code files/contexts/*.md}.
 */
public final class ContextualFileStore {

    private static final String TAG = "ContextualFileStore";
    private static final String ASSET_DIR = "contexts";
    private static final int MAX_INJECT_CHARS = 12_000;

    public static final class Meta {
        public final String keyword;
        public final String filename;
        public final long lastModified;
        public final long sizeBytes;
        public final boolean loaded;

        Meta(String keyword, String filename, long lastModified, long sizeBytes, boolean loaded) {
            this.keyword = keyword;
            this.filename = filename;
            this.lastModified = lastModified;
            this.sizeBytes = sizeBytes;
            this.loaded = loaded;
        }
    }

    private static ContextualFileStore instance;

    private final Context appContext;
    private final File contextsDir;
    private final File activeFile;
    private final Map<String, String> keywords = new HashMap<>();
    private final LinkedHashSet<String> loaded = new LinkedHashSet<>();

    private ContextualFileStore(Context context) {
        appContext = context.getApplicationContext();
        contextsDir = new File(appContext.getFilesDir(), "contexts");
        if (!contextsDir.exists()) contextsDir.mkdirs();
        activeFile = new File(contextsDir, "active.json");
        registerDefaultKeywords();
        seedFromAssetsIfNeeded();
        loadActiveState();
        ContextSearchIndex.getInstance(appContext).indexAllAsync();
    }

    public static synchronized ContextualFileStore getInstance(Context context) {
        if (instance == null) instance = new ContextualFileStore(context);
        return instance;
    }

    public static synchronized void resetInstanceForTests() {
        ContextSearchIndex.resetInstanceForTests();
        instance = null;
    }

    private void registerDefaultKeywords() {
        keywords.put("orion", "orion-context.md");
        keywords.put("boucherie", "boucherie-context.md");
        keywords.put("fableris", "fableris-context.md");
        keywords.put("olympos", "olympos-context.md");
        keywords.put("irisforge", "irisforge-context.md");
        keywords.put("pegase", "pegase-context.md");
        keywords.put("pégase", "pegase-context.md");
        keywords.put("f1", "f1-context.md");
        keywords.put("formule 1", "f1-context.md");
        keywords.put("formule1", "f1-context.md");
        keywords.put("grand prix", "f1-context.md");
        keywords.put("f1_fan", "f1-fan-context.md");
        keywords.put("fan f1", "f1-fan-context.md");
        keywords.put("mémoire f1", "f1-fan-context.md");
        keywords.put("memoire f1", "f1-fan-context.md");
        keywords.put("corrections", CorrectionsStore.FILENAME);
        keywords.put("correction", CorrectionsStore.FILENAME);
    }

    /** Enregistre le mot-clé corrections → {@code files/diag/corrections.md}. */
    public synchronized void ensureCorrectionsKeyword() {
        keywords.put("corrections", CorrectionsStore.FILENAME);
        keywords.put("correction", CorrectionsStore.FILENAME);
    }

    /** Fichier réel d'un contexte (corrections.md vit sous diag/). */
    public File resolveContextFile(String filename) {
        if (filename == null) return null;
        if (CorrectionsStore.FILENAME.equals(filename)) {
            return PegaseFileSystem.get(appContext).correctionsMd();
        }
        return new File(contextsDir, filename);
    }

    /** Résout un mot-clé ou un stem de fichier vers un nom de fichier. */
    public synchronized String resolveKeyword(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        String k = fold(raw.trim());
        k = k.replaceAll("\\s+", " ").trim();
        // « le contexte Orion » → strip articles + mot « contexte »
        for (int i = 0; i < 4; i++) {
            String next = k.replaceFirst("^(le|la|les|du|de|des|contexte)\\s+", "");
            if (next.equals(k)) break;
            k = next.trim();
        }
        if (k.isEmpty()) return null;

        String byKey = keywords.get(k);
        if (byKey != null) return byKey;

        // "orion-context" / "orion context"
        String compact = k.replace(" ", "-").replace("_", "-");
        if (!compact.endsWith(".md")) {
            String withSuffix = compact.endsWith("-context")
                    ? compact + ".md"
                    : compact + "-context.md";
            File f = new File(contextsDir, withSuffix);
            if (f.isFile()) return withSuffix;
        } else {
            File f = new File(contextsDir, compact);
            if (f.isFile()) return compact;
        }

        // Préfixe : seulement si le mot tapé est assez long (évite « peg » → pegase)
        if (k.length() >= 4) {
            for (Map.Entry<String, String> e : keywords.entrySet()) {
                if (e.getKey().equals(k) || e.getKey().startsWith(k)) {
                    return e.getValue();
                }
            }
        }
        return null;
    }

    public synchronized List<Meta> listContexts() {
        List<Meta> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Map.Entry<String, String> e : keywords.entrySet()) {
            if ("pégase".equals(e.getKey())) continue; // alias
            String filename = e.getValue();
            if (!seen.add(filename)) continue;
            File f = resolveContextFile(filename);
            out.add(new Meta(
                    e.getKey(),
                    filename,
                    f != null && f.exists() ? f.lastModified() : 0L,
                    f != null && f.exists() ? f.length() : 0L,
                    loaded.contains(filename)));
        }
        File[] files = contextsDir.listFiles((dir, name) ->
                name != null && name.endsWith(".md"));
        if (files != null) {
            for (File f : files) {
                if (seen.contains(f.getName())) continue;
                String stem = f.getName().replace("-context.md", "").replace(".md", "");
                out.add(new Meta(stem, f.getName(), f.lastModified(), f.length(),
                        loaded.contains(f.getName())));
            }
        }
        return out;
    }

    public synchronized String readFile(String filename) {
        if (filename == null) return null;
        File f = resolveContextFile(filename);
        if (f == null || !f.isFile()) return null;
        return readUtf8(f);
    }

    public synchronized String readByKeyword(String keyword) {
        String filename = resolveKeyword(keyword);
        if (filename == null) return null;
        return readFile(filename);
    }

    /**
     * Charge un contexte en session (persisté dans active.json).
     * @return message oral, ou null si introuvable
     */
    public synchronized String load(String keyword) {
        String filename = resolveKeyword(keyword);
        if (filename == null) return null;
        File f = resolveContextFile(filename);
        if (f == null || !f.isFile()) {
            // corrections.md : créer au besoin
            if (CorrectionsStore.FILENAME.equals(filename)) {
                CorrectionsStore.read(appContext);
                f = resolveContextFile(filename);
            }
        }
        if (f == null || !f.isFile()) return null;
        loaded.add(filename);
        saveActiveState();
        String display = displayName(filename);
        return "Contexte " + display + " chargé.";
    }

    public synchronized List<String> loadMultiple(List<String> keywordList) {
        List<String> loadedNames = new ArrayList<>();
        if (keywordList == null) return loadedNames;
        for (String k : keywordList) {
            String msg = load(k);
            if (msg != null) {
                String filename = resolveKeyword(k);
                if (filename != null) loadedNames.add(displayName(filename));
            }
        }
        return loadedNames;
    }

    public synchronized String unload(String keyword) {
        if (keyword == null || fold(keyword).contains("tout")) {
            int n = loaded.size();
            loaded.clear();
            saveActiveState();
            return n == 0
                    ? "Aucun contexte n'était chargé."
                    : "Tous les contextes sont déchargés.";
        }
        String filename = resolveKeyword(keyword);
        if (filename == null || !loaded.remove(filename)) {
            return "Ce contexte n'était pas chargé.";
        }
        saveActiveState();
        return "Contexte " + displayName(filename) + " déchargé.";
    }

    /**
     * Supprime un .md de contexte du disque (et le retire de la session / index).
     * @return message court pour toast, ou null si introuvable
     */
    public synchronized String deleteFile(String keywordOrFilename) {
        if (keywordOrFilename == null || keywordOrFilename.trim().isEmpty()) return null;
        String filename = resolveKeyword(keywordOrFilename);
        if (filename == null) {
            String raw = keywordOrFilename.trim();
            if (raw.endsWith(".md")) filename = raw;
            else return null;
        }
        if (filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
            return null;
        }
        loaded.remove(filename);
        saveActiveState();
        File f = resolveContextFile(filename);
        boolean deleted = f != null && f.isFile() && f.delete();
        try {
            ContextSearchIndex.getInstance(appContext).deleteFilename(filename);
        } catch (Exception ignored) {}
        // Mots-clés dynamiques uniquement (pas les alias intégrés)
        List<String> toRemove = new ArrayList<>();
        for (Map.Entry<String, String> e : keywords.entrySet()) {
            if (filename.equals(e.getValue()) && !isBuiltinKeyword(e.getKey())) {
                toRemove.add(e.getKey());
            }
        }
        for (String k : toRemove) keywords.remove(k);
        if (!deleted) return null;
        return "Supprimé : " + filename;
    }

    private static boolean isBuiltinKeyword(String key) {
        if (key == null) return false;
        switch (key) {
            case "orion":
            case "boucherie":
            case "fableris":
            case "olympos":
            case "irisforge":
            case "pegase":
            case "pégase":
            case "f1":
            case "formule 1":
            case "formule1":
            case "grand prix":
            case "f1_fan":
            case "fan f1":
            case "mémoire f1":
            case "memoire f1":
            case "corrections":
            case "correction":
                return true;
            default:
                return false;
        }
    }

    public synchronized List<String> getLoadedFilenames() {
        return new ArrayList<>(loaded);
    }

    public synchronized List<String> getLoadedDisplayNames() {
        List<String> out = new ArrayList<>();
        for (String f : loaded) out.add(displayName(f));
        return out;
    }

    /** Bloc complet des .md chargés (budget défaut {@link #MAX_INJECT_CHARS}). */
    public synchronized String buildPromptSection() {
        return buildPromptSection(MAX_INJECT_CHARS);
    }

    /**
     * Corps des contextes chargés, à coller près du message utilisateur.
     * @param maxChars budget caractères (corps), 0 = défaut
     */
    public synchronized String buildPromptSection(int maxChars) {
        if (loaded.isEmpty()) return "";
        int limit = maxChars > 0 ? maxChars : MAX_INJECT_CHARS;
        StringBuilder sb = new StringBuilder();
        sb.append("=== Documents joints (sources de vérité) ===\n");
        sb.append("L'utilisateur a explicitement attaché ces fichiers .md. "
                + "Lis-les et base ta réponse dessus. "
                + "Ne les ignore pas, ne dis pas que tu n'as pas accès au fichier.\n");
        int used = 0;
        for (String filename : loaded) {
            String content = readFile(filename);
            if (content == null || content.isEmpty()) continue;
            String block = "### " + displayName(filename) + " (" + filename + ")\n"
                    + content.trim() + "\n\n";
            if (used + block.length() > limit) {
                int remain = limit - used;
                if (remain > 200) {
                    sb.append(block, 0, remain).append("\n…[tronqué]\n");
                }
                break;
            }
            sb.append(block);
            used += block.length();
        }
        return used == 0 ? "" : sb.toString();
    }

    /** Rappel court pour le prompt système (sans recopier tout le .md). */
    public synchronized String buildPromptPointer() {
        List<String> names = getLoadedDisplayNames();
        if (names.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("\n--- Documents joints ---\n");
        sb.append("Fichiers .md attachés pour ce tour (texte collé au message utilisateur) : ");
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(names.get(i));
        }
        sb.append(".\nRespecte ces documents ; ce sont les sources de vérité demandées.\n");
        return sb.toString();
    }

    public synchronized void save(String keyword, String content) {
        String filename = resolveKeyword(keyword);
        if (filename == null) {
            String k = fold(keyword == null ? "projet" : keyword);
            filename = k.replace(' ', '-') + "-context.md";
            keywords.put(k, filename);
        }
        File target = resolveContextFile(filename);
        if (target == null) target = new File(contextsDir, filename);
        writeUtf8(target, content == null ? "" : content);
        ContextSearchIndex.getInstance(appContext).indexFile(target);
    }

    /** true si un fichier de contexte existe déjà pour ce mot-clé. */
    public synchronized boolean contextExists(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) return false;
        String filename = resolveKeyword(keyword);
        if (filename == null) {
            String k = fold(keyword);
            File candidate = new File(contextsDir, k.replace(' ', '-') + "-context.md");
            return candidate.isFile();
        }
        File f = resolveContextFile(filename);
        return f != null && f.isFile();
    }

    /** Recherche sémantique dans tous les .md indexés. */
    public List<ContextSearchIndex.Hit> search(String query, int topK) {
        return search(query, topK, ContextSearchIndex.MIN_SCORE);
    }

    public List<ContextSearchIndex.Hit> search(String query, int topK, float minScore) {
        return ContextSearchIndex.getInstance(appContext)
                .search(query, topK, minScore);
    }

    /**
     * Contenus des contextes actuellement chargés (pour Orion / injection).
     * Un élément = corps Markdown d'un fichier chargé.
     */
    public synchronized List<String> getLoadedContexts() {
        List<String> out = new ArrayList<>();
        for (String filename : loaded) {
            String content = readFile(filename);
            if (content == null || content.trim().isEmpty()) continue;
            out.add("### " + displayName(filename) + " (" + filename + ")\n"
                    + content.trim());
        }
        return out;
    }

    public String formatSearchForSpeech(String query, int topK) {
        ContextSearchIndex idx = ContextSearchIndex.getInstance(appContext);
        List<ContextSearchIndex.Hit> hits = idx.search(query, topK, ContextSearchIndex.MIN_SCORE);
        return idx.formatSearchForSpeech(hits, query);
    }

    public synchronized String formatListForSpeech() {
        List<Meta> list = listContexts();
        if (list.isEmpty()) {
            return "Tu n'as aucun fichier de contexte pour l'instant.";
        }
        StringBuilder sb = new StringBuilder("Voici tes fichiers de contexte : ");
        for (int i = 0; i < list.size(); i++) {
            Meta m = list.get(i);
            if (i > 0) sb.append(", ");
            sb.append(m.keyword);
            if (m.loaded) sb.append(" (chargé)");
            if (m.lastModified > 0) {
                sb.append(", modifié ").append(formatRelative(m.lastModified));
            }
        }
        sb.append(".");
        return sb.toString();
    }

    public synchronized String formatLoadedForSpeech() {
        List<String> names = getLoadedDisplayNames();
        if (names.isEmpty()) return "Aucun contexte n'est chargé.";
        if (names.size() == 1) return names.get(0) + " est actif.";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) {
                sb.append(i == names.size() - 1 ? " et " : ", ");
            }
            sb.append(names.get(i));
        }
        sb.append(" sont actifs.");
        return sb.toString();
    }

    public File getContextsDir() {
        return contextsDir;
    }

    private void seedFromAssetsIfNeeded() {
        try {
            String[] assets = appContext.getAssets().list(ASSET_DIR);
            if (assets == null) return;
            for (String name : assets) {
                if (name == null || !name.endsWith(".md")) continue;
                File dest = new File(contextsDir, name);
                if (dest.exists()) continue;
                try (InputStream in = appContext.getAssets().open(ASSET_DIR + "/" + name);
                     FileOutputStream out = new FileOutputStream(dest)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                Log.i(TAG, "Contexte seed : " + name);
            }
        } catch (Exception e) {
            Log.w(TAG, "seedFromAssetsIfNeeded", e);
        }
    }

    private void loadActiveState() {
        loaded.clear();
        if (!activeFile.exists()) return;
        try {
            String raw = readUtf8(activeFile);
            if (raw == null || raw.isEmpty()) return;
            // Format simple : une ligne = un filename
            for (String line : raw.split("\n")) {
                String name = line.trim();
                if (name.isEmpty() || name.startsWith("#")) continue;
                if (new File(contextsDir, name).isFile()) {
                    loaded.add(name);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "loadActiveState", e);
        }
    }

    private void saveActiveState() {
        StringBuilder sb = new StringBuilder();
        for (String name : loaded) {
            sb.append(name).append('\n');
        }
        writeUtf8(activeFile, sb.toString());
    }

    private static String displayName(String filename) {
        if (filename == null) return "";
        String s = filename.replace("-context.md", "").replace(".md", "");
        if (s.isEmpty()) return filename;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String formatRelative(long ms) {
        long days = (System.currentTimeMillis() - ms) / (24L * 60 * 60 * 1000);
        if (days <= 0) return "aujourd'hui";
        if (days == 1) return "hier";
        if (days < 7) return "il y a " + days + " jours";
        return new SimpleDateFormat("d MMM", Locale.FRENCH).format(new Date(ms));
    }

    static String fold(String s) {
        if (s == null) return "";
        String t = s.toLowerCase(Locale.ROOT);
        t = t.replace('é', 'e').replace('è', 'e').replace('ê', 'e').replace('ë', 'e');
        t = t.replace('à', 'a').replace('â', 'a').replace('ä', 'a');
        t = t.replace('ù', 'u').replace('û', 'u').replace('ü', 'u');
        t = t.replace('ô', 'o').replace('ö', 'o');
        t = t.replace('î', 'i').replace('ï', 'i');
        t = t.replace('ç', 'c');
        t = t.replace('’', '\'').replace('\'', ' ');
        return t.trim();
    }

    static String readUtf8(File file) {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(line);
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** Lecture UTF-8 pour l'index (même package / tests). */
    public static String readUtf8Public(File file) {
        return readUtf8(file);
    }

    private static void writeUtf8(File file, String content) {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(TAG, "writeUtf8 " + file.getName(), e);
        }
    }

    /** Tests : écrit un fichier sans passer par les assets. */
    public synchronized void writeForTests(String filename, String content) {
        writeUtf8(new File(contextsDir, filename), content == null ? "" : content);
    }

    public synchronized void clearLoadedForTests() {
        loaded.clear();
        saveActiveState();
    }

    public Map<String, String> keywordsSnapshotForTests() {
        return Collections.unmodifiableMap(new HashMap<>(keywords));
    }
}
