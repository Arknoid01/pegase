package com.pegasuscorp.orbe.orion.search;

import android.text.TextUtils;

import com.pegasuscorp.orbe.orion.OrionProjectStore;
import com.pegasuscorp.orbe.rag.EmbeddingEngine;
import com.pegasuscorp.orbe.rag.VectorStore;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Localise un fichier projet Orion avec RAG + recherche ligne à ligne.
 */
public final class OrionFileSearcher {

    private static final int TOP_K = 5;
    private static final float MIN_SCORE = 0.15f;

    private final OrionProjectStore projectStore;
    private final EmbeddingEngine embedder;
    private final VectorStore vectorStore;
    private final OrionCodeIndexer codeIndexer;

    public OrionFileSearcher(OrionProjectStore projectStore,
            EmbeddingEngine embedder, VectorStore vectorStore) {
        this(projectStore, embedder, vectorStore, null);
    }

    public OrionFileSearcher(OrionProjectStore projectStore,
            EmbeddingEngine embedder, VectorStore vectorStore,
            OrionCodeIndexer codeIndexer) {
        this.projectStore = projectStore;
        this.embedder = embedder;
        this.vectorStore = vectorStore;
        this.codeIndexer = codeIndexer;
    }

    /** Constructeur tolérant pour tests / fallback lexical. */
    public OrionFileSearcher(OrionProjectStore projectStore, VectorStore vectorStore) {
        this(projectStore, null, vectorStore, null);
    }

    public OrionFileSearcher withCodeIndexer(OrionCodeIndexer indexer) {
        return new OrionFileSearcher(projectStore, embedder, vectorStore, indexer);
    }

    /**
     * Trouve le fichier et la ligne exacte pour une intention donnée.
     */
    public Optional<FileLocation> find(String projectName, String keyword) {
        if (projectStore == null || TextUtils.isEmpty(projectName) || TextUtils.isEmpty(keyword)) {
            return Optional.empty();
        }
        List<LoadedFile> files = loadFiles(projectName);
        if (files.isEmpty()) return Optional.empty();

        if (hasJava(files) && codeIndexer != null) {
            Optional<CodeLocation> code = codeIndexer.findCode(keyword);
            if (code.isPresent()) {
                CodeLocation loc = code.get();
                return Optional.of(new FileLocation(loc.filename, loc.startLine, loc.snippet));
            }
        }

        return findInWebFiles(projectName, keyword, files);
    }

    private Optional<FileLocation> findInWebFiles(String projectName, String keyword,
            List<LoadedFile> files) {
        LoadedFile best = findBestFile(projectName, keyword, files);
        if (best == null) return Optional.empty();

        int line = findLine(best.content, keyword);
        if (line < 0) {
            return Optional.of(new FileLocation(best.name, -1, best.content));
        }
        String snippet = extractSnippet(best.content, line, 10);
        return Optional.of(new FileLocation(best.name, line, snippet));
    }

    public static String vectorKey(String projectName, String filename) {
        return "orion-file:" + safe(projectName) + ":" + safe(filename);
    }

    private static boolean hasJava(List<LoadedFile> files) {
        for (LoadedFile f : files) {
            if (f != null && OrionCodeIndexer.isJavaFile(f.name)) return true;
        }
        return false;
    }

    private LoadedFile findBestFile(String projectName, String keyword, List<LoadedFile> files) {
        LoadedFile ragHit = findBestFileByVector(projectName, keyword, files);
        if (ragHit != null) return ragHit;
        return findBestFileLexical(keyword, files);
    }

    private LoadedFile findBestFileByVector(String projectName, String keyword, List<LoadedFile> files) {
        if (vectorStore == null || embedder == null) return null;
        try {
            float[] queryVector = embedder.embed(keyword);
            List<VectorStore.Hit> hits = vectorStore.search(queryVector, TOP_K, MIN_SCORE,
                    VectorStore.NS_ORION_FILES);
            if (hits.isEmpty()) return null;
            for (VectorStore.Hit hit : hits) {
                LoadedFile match = matchHit(projectName, hit, files);
                if (match != null) return match;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private LoadedFile matchHit(String projectName, VectorStore.Hit hit, List<LoadedFile> files) {
        if (hit == null) return null;
        String filename = hit.memoryKey;
        try {
            if (!TextUtils.isEmpty(hit.payload)) {
                JSONObject payload = new JSONObject(hit.payload);
                String hitProject = payload.optString("project", "");
                if (!safe(projectName).equals(safe(hitProject))) return null;
                filename = payload.optString("filename", filename);
            }
        } catch (Exception ignored) {
        }
        for (LoadedFile f : files) {
            if (f.name.equalsIgnoreCase(filename)) return f;
        }
        return null;
    }

    private LoadedFile findBestFileLexical(String keyword, List<LoadedFile> files) {
        String kw = fold(keyword);
        if (kw.isEmpty()) return null;
        LoadedFile best = null;
        int bestScore = Integer.MIN_VALUE;
        for (LoadedFile f : files) {
            int score = lexicalScore(f, kw);
            if (score > bestScore) {
                bestScore = score;
                best = f;
            }
        }
        if (bestScore <= 0) return null;
        return best;
    }

    private int lexicalScore(LoadedFile f, String kw) {
        int score = 0;
        String name = fold(f.name);
        String content = fold(f.content);
        if (name.contains(kw)) score += 8;
        if (content.contains(kw)) score += 10;
        for (String token : kw.split("\\s+")) {
            if (token.isEmpty()) continue;
            if (name.contains(token)) score += 4;
            if (content.contains(token)) score += 2;
        }
        return score;
    }

    private List<LoadedFile> loadFiles(String projectName) {
        List<LoadedFile> out = new ArrayList<>();
        List<OrionProjectStore.ProjectFile> files = projectStore.getProjectFiles(projectName);
        for (OrionProjectStore.ProjectFile pf : files) {
            if (pf == null || TextUtils.isEmpty(pf.name) || pf.file == null || !pf.file.isFile()) continue;
            try {
                out.add(new LoadedFile(pf.name, OrionProjectStore.readUtf8(pf.file)));
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    int findLine(String content, String keyword) {
        if (TextUtils.isEmpty(content) || TextUtils.isEmpty(keyword)) return -1;
        String[] lines = content.split("\\r?\\n", -1);
        String kw = fold(keyword);
        for (int i = 0; i < lines.length; i++) {
            if (fold(lines[i]).contains(kw)) return i + 1;
        }
        for (String token : kw.split("\\s+")) {
            if (token.isEmpty()) continue;
            for (int i = 0; i < lines.length; i++) {
                if (fold(lines[i]).contains(token)) return i + 1;
            }
        }
        return -1;
    }

    String extractSnippet(String content, int line, int radius) {
        if (TextUtils.isEmpty(content) || line < 1) return "";
        String[] lines = content.split("\\r?\\n", -1);
        int start = Math.max(0, line - 1 - Math.max(0, radius));
        int end = Math.min(lines.length, line + Math.max(0, radius));
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < end; i++) {
            sb.append(i == line - 1 ? "-> " : "   ");
            sb.append(lines[i]).append('\n');
        }
        return sb.toString().trim();
    }

    private static String fold(String text) {
        if (text == null) return "";
        return text.toLowerCase(Locale.ROOT)
                .replace('é', 'e').replace('è', 'e').replace('ê', 'e')
                .replace('à', 'a').replace('â', 'a')
                .replace('ô', 'o').replace('ù', 'u').replace('û', 'u')
                .replace('î', 'i').replace('ï', 'i')
                .replace('ç', 'c')
                .replace('_', ' ')
                .replaceAll("[^a-z0-9.\\- ]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String safe(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
    }

    private static final class LoadedFile {
        final String name;
        final String content;

        LoadedFile(String name, String content) {
            this.name = name == null ? "" : name;
            this.content = content == null ? "" : content;
        }
    }
}
