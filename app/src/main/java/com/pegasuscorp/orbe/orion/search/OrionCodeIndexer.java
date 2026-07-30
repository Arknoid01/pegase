package com.pegasuscorp.orbe.orion.search;

import android.text.TextUtils;

import com.pegasuscorp.orbe.diag.Trace;
import com.pegasuscorp.orbe.rag.EmbeddingEngine;
import com.pegasuscorp.orbe.rag.VectorStore;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;

import org.json.JSONObject;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Index AST JavaParser des fichiers .java d'un projet Orion actif.
 */
public final class OrionCodeIndexer {

    public static final String NS = VectorStore.NS_ORION_CODE;
    public static final float MIN_SCORE = 0.72f;

    /** Fichier Java projet Orion (contenu en mémoire). */
    public static final class JavaFile {
        public final String name;
        public final String content;

        public JavaFile(String name, String content) {
            this.name = name == null ? "" : name;
            this.content = content == null ? "" : content;
        }
    }

    /** Embedder injectable (EmbeddingEngine en prod, fake en tests). */
    public interface VectorEmbedder {
        float[] embed(String text) throws Exception;
    }

    private final String projectName;
    private final VectorEmbedder embedder;
    private final VectorStore vectorStore;

    public OrionCodeIndexer(String projectName, EmbeddingEngine embedder,
            VectorStore vectorStore) {
        this(projectName, embedder == null ? null : embedder::embed, vectorStore);
    }

    OrionCodeIndexer(String projectName, VectorEmbedder embedder, VectorStore vectorStore) {
        this.projectName = projectName == null ? "" : projectName.trim();
        this.embedder = embedder;
        this.vectorStore = vectorStore;
    }

    /** Tests : indexation sans embedder (parse seulement). */
    OrionCodeIndexer(String projectName, VectorStore vectorStore) {
        this(projectName, (VectorEmbedder) null, vectorStore);
    }

    public void indexProject(List<JavaFile> javaFiles) {
        if (javaFiles == null || vectorStore == null) return;
        for (JavaFile f : javaFiles) {
            if (f == null || !isJavaFile(f.name)) continue;
            try {
                indexFile(f);
            } catch (Exception e) {
                Trace.orionIndexError(f.name, e.getMessage());
            }
        }
    }

    public void reindexFile(JavaFile f) {
        if (f == null || !isJavaFile(f.name) || vectorStore == null) return;
        vectorStore.deleteKeysWithPrefix(NS, keyPrefix(f.name));
        try {
            indexFile(f);
        } catch (Exception e) {
            Trace.orionIndexError(f.name, e.getMessage());
        }
    }

    public void purgeFile(String filename) {
        if (vectorStore == null || TextUtils.isEmpty(filename)) return;
        vectorStore.deleteKeysWithPrefix(NS, keyPrefix(filename));
    }

    public Optional<CodeLocation> findCode(String query) {
        if (embedder == null || vectorStore == null || TextUtils.isEmpty(query)) {
            return Optional.empty();
        }
        try {
            float[] queryVector = embedder.embed(query);
            List<VectorStore.Hit> hits = vectorStore.search(queryVector, 3, MIN_SCORE, NS);
            for (VectorStore.Hit hit : hits) {
                CodeLocation loc = hitToLocation(hit);
                if (loc != null) return Optional.of(loc);
            }
        } catch (Exception ignored) {
        }
        return Optional.empty();
    }

    void indexFile(JavaFile f) throws Exception {
        if (f == null || TextUtils.isEmpty(f.content)) return;
        CompilationUnit cu = StaticJavaParser.parse(f.content);
        for (MethodDeclaration m : cu.findAll(MethodDeclaration.class)) {
            if (m.getRange().isEmpty()) continue;
            com.github.javaparser.Range r = m.getRange().get();
            String snippet = extractLines(f.content, r.begin.line, r.end.line);
            vectorize(new CodeLocation(
                    f.name, m.getNameAsString(), r.begin.line, r.end.line, snippet, "method"));
        }
        for (FieldDeclaration fd : cu.findAll(FieldDeclaration.class)) {
            if (fd.getRange().isEmpty()) continue;
            com.github.javaparser.Range r = fd.getRange().get();
            for (VariableDeclarator v : fd.getVariables()) {
                vectorize(new CodeLocation(
                        f.name, v.getNameAsString(), r.begin.line, r.end.line,
                        fd.toString(), "field"));
            }
        }
    }

    private void vectorize(CodeLocation loc) throws Exception {
        if (embedder == null || vectorStore == null || loc == null) return;
        String snippetShort = loc.snippet.length() > 200
                ? loc.snippet.substring(0, 200) : loc.snippet;
        String text = loc.methodName + " " + loc.filename + " " + loc.kind + " " + snippetShort;
        float[] vector = embedder.embed(text);
        String snippetPayload = loc.snippet.length() > 500
                ? loc.snippet.substring(0, 500) : loc.snippet;
        JSONObject payload = new JSONObject()
                .put("project", projectName)
                .put("filename", loc.filename)
                .put("method", loc.methodName)
                .put("startLine", String.valueOf(loc.startLine))
                .put("endLine", String.valueOf(loc.endLine))
                .put("kind", loc.kind)
                .put("snippet", snippetPayload);
        vectorStore.upsert(vectorKey(projectName, loc.filename, loc.methodName), vector, NS,
                payload.toString());
    }

    CodeLocation hitToLocation(VectorStore.Hit hit) {
        if (hit == null || TextUtils.isEmpty(hit.payload)) return null;
        try {
            JSONObject o = new JSONObject(hit.payload);
            String proj = o.optString("project", "");
            if (!TextUtils.isEmpty(projectName)
                    && !projectName.equalsIgnoreCase(proj)) {
                return null;
            }
            return new CodeLocation(
                    o.optString("filename", ""),
                    o.optString("method", ""),
                    parseInt(o.optString("startLine", "0")),
                    parseInt(o.optString("endLine", "0")),
                    o.optString("snippet", ""),
                    o.optString("kind", "method"));
        } catch (Exception e) {
            return null;
        }
    }

    public static String vectorKey(String filename, String symbol) {
        return vectorKey("", filename, symbol);
    }

    static String vectorKey(String projectName, String filename, String symbol) {
        return "orion-code:" + safe(projectName) + ":" + safe(filename)
                + "#" + safe(symbol);
    }

    String keyPrefix(String filename) {
        return "orion-code:" + safe(projectName) + ":" + safe(filename) + "#";
    }

    static String extractLines(String content, int start, int end) {
        if (content == null) return "";
        String[] lines = content.split("\\r?\\n", -1);
        StringBuilder sb = new StringBuilder();
        int from = Math.max(0, start - 1);
        int to = Math.min(lines.length, end);
        for (int i = from; i < to; i++) {
            sb.append(lines[i]).append('\n');
        }
        return sb.toString().trim();
    }

    static boolean isJavaFile(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).endsWith(".java");
    }

    private static int parseInt(String v) {
        try {
            return Integer.parseInt(v);
        } catch (Exception e) {
            return 0;
        }
    }

    private static String safe(String text) {
        return text == null ? "" : text.trim();
    }
}
