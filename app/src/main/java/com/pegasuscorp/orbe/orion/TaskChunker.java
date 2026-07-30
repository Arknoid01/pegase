package com.pegasuscorp.orbe.orion;

import android.content.Context;
import android.text.TextUtils;

import com.pegasuscorp.orbe.diag.Trace;
import com.pegasuscorp.orbe.orion.prompt.OrionMode;
import com.pegasuscorp.orbe.orion.prompt.PromptCompiler;
import com.pegasuscorp.orbe.orion.prompt.ResolvedTask;
import com.pegasuscorp.orbe.orion.search.OrionCodeIndexService;
import com.pegasuscorp.orbe.orion.search.OrionCodeIndexer;
import com.pegasuscorp.orbe.orion.search.OrionFileSearcher;
import com.pegasuscorp.orbe.rag.EmbeddingEngine;
import com.pegasuscorp.orbe.rag.VectorStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Découpe une mission LARGE en chunks séquentiels (max 6).
 * Chaque chunk = 1 fichier max, complexité forcée à {@link TaskComplexity#SIMPLE}.
 */
public final class TaskChunker {

    private static final int MAX_CHUNKS = 6;

    /** Appel LLM éphémère (GPT-120 / Pégase). */
    public interface ChunkPlanner {
        String plan(String prompt) throws Exception;
    }

    private final OrionFileSearcher searcher;
    private final OrionProjectStore projectStore;

    public TaskChunker(OrionFileSearcher searcher, OrionProjectStore projectStore) {
        this.searcher = searcher;
        this.projectStore = projectStore;
    }

    public static TaskChunker create(Context ctx) {
        OrionProjectStore store = OrionProjectStore.get(ctx);
        String active = store.getActiveProject();
        OrionCodeIndexer codeIndexer = TextUtils.isEmpty(active)
                ? null
                : OrionCodeIndexService.get().indexerFor(ctx, active);
        try {
            OrionFileSearcher searcher = new OrionFileSearcher(
                    store, EmbeddingEngine.get(ctx), new VectorStore(ctx), codeIndexer);
            return new TaskChunker(searcher, store);
        } catch (Exception e) {
            OrionFileSearcher searcher = new OrionFileSearcher(store, new VectorStore(ctx))
                    .withCodeIndexer(codeIndexer);
            return new TaskChunker(searcher, store);
        }
    }

    /**
     * Découpe une mission LARGE/MASSIVE en chunks séquentiels enrichis.
     */
    public List<TaskChunk> chunk(Context ctx, ResolvedTask parent, ChunkPlanner planner)
            throws Exception {
        if (parent == null) return Collections.emptyList();
        String json = planner.plan(buildDecompositionPrompt(parent));
        List<ChunkSpec> specs = parseChunkSpecs(json, parent);
        String projectName = projectStore != null ? projectStore.getActiveProject() : "";
        List<TaskChunk> chunks = new ArrayList<>();
        for (int i = 0; i < specs.size(); i++) {
            ChunkSpec spec = specs.get(i);
            ResolvedTask chunkTask = enrichChunk(ctx, parent, spec, i + 1, specs.size(), projectName);
            chunks.add(new TaskChunk(i + 1, specs.size(), chunkTask, spec.summary));
        }
        return chunks;
    }

    static List<ChunkSpec> parseChunkSpecs(String json, ResolvedTask parent) {
        String fallbackRaw = parent != null && !TextUtils.isEmpty(parent.rawInput)
                ? parent.rawInput
                : (parent != null ? parent.mission : "Mission");
        try {
            JSONArray arr = new JSONArray(extractJsonArray(json));
            List<ChunkSpec> specs = new ArrayList<>();
            int limit = Math.min(arr.length(), MAX_CHUNKS);
            for (int i = 0; i < limit; i++) {
                JSONObject o = arr.getJSONObject(i);
                String keyword = normalizeNullableKeyword(o.optString("keyword", null));
                specs.add(new ChunkSpec(
                        o.optString("summary", "Étape " + (i + 1)),
                        keyword,
                        o.optString("file", null)));
            }
            if (!specs.isEmpty()) return specs;
        } catch (Exception e) {
            Trace.orionChunkError(e.getMessage());
        }
        return List.of(new ChunkSpec(fallbackRaw, null, null));
    }

    static String extractJsonArray(String raw) {
        if (raw == null) return "[]";
        String t = raw.trim();
        int start = t.indexOf('[');
        int end = t.lastIndexOf(']');
        if (start >= 0 && end > start) return t.substring(start, end + 1);
        return t;
    }

    static String buildDecompositionPrompt(ResolvedTask task) {
        String context = buildContextSummary(task);
        return "Tu es architecte senior web (HTML/CSS/JS).\n"
                + "Décompose cette mission en sous-tâches séquentielles.\n"
                + "Règles :\n"
                + "- Chaque sous-tâche : 1 fichier max, 1 objectif précis\n"
                + "- Ordre : du plus indépendant au plus dépendant\n"
                + "- Format JSON : [{\"summary\":\"...\",\"keyword\":\"...\",\"file\":\"...\"}]\n"
                + "- Maximum 6 chunks\n"
                + "- keyword : mot-clé technique ciblé (null si générique)\n"
                + "Mission : " + nz(task.rawInput, task.mission) + "\n"
                + "Contexte projet : " + context;
    }

    private ResolvedTask enrichChunk(Context ctx, ResolvedTask parent, ChunkSpec spec,
            int index, int total, String projectName) {
        ResolvedTask.Builder b = ResolvedTask.builder()
                .mission("Étape " + index + "/" + total)
                .objective(spec.summary)
                .rawInput(spec.summary)
                .action(spec.summary)
                .exclusion("Ne modifier qu'un seul fichier pour cette étape")
                .validation("Patch minimal — étape " + index + " uniquement")
                .complexity(TaskComplexity.SIMPLE);
        if (!TextUtils.isEmpty(spec.keyword)) {
            b.keyword(spec.keyword);
        }
        if (parent != null && !TextUtils.isEmpty(parent.context)) {
            b.context(parent.context);
        }
        if (!TextUtils.isEmpty(projectName) && !TextUtils.isEmpty(spec.keyword)) {
            searcher.find(projectName, spec.keyword).ifPresent(loc -> {
                if (!TextUtils.isEmpty(loc.filename)) {
                    b.context((parent != null ? nz(parent.context, "") + "\n" : "")
                            + "Fichier ciblé : " + loc.filename
                            + (loc.line > 0 ? " (ligne " + loc.line + ")" : ""));
                }
            });
        }
        OrionMode parentMode = parent != null && parent.mode != null
                ? parent.mode
                : OrionMode.PATCH;
        b.mode(parentMode);
        String compiled = PromptCompiler.compile(b.build());
        ResolvedTask resolved = PromptCompiler.resolve(ctx, compiled, spec.summary, parentMode);
        return ResolvedTask.builder()
                .from(resolved)
                .complexity(TaskComplexity.SIMPLE)
                .mode(parentMode)
                .build();
    }

    private static String buildContextSummary(ResolvedTask task) {
        if (task == null) return "";
        StringBuilder sb = new StringBuilder();
        if (!TextUtils.isEmpty(task.context)) sb.append(task.context.trim());
        if (!TextUtils.isEmpty(task.mission)) {
            if (sb.length() > 0) sb.append(" — ");
            sb.append(task.mission.trim());
        }
        if (sb.length() == 0 && !TextUtils.isEmpty(task.objective)) {
            sb.append(task.objective.trim());
        }
        return sb.toString();
    }

    private static String normalizeNullableKeyword(String v) {
        if (v == null) return null;
        String t = v.trim();
        if (t.isEmpty() || "null".equalsIgnoreCase(t)) return null;
        return t;
    }

    private static String nz(String v, String fallback) {
        return TextUtils.isEmpty(v) ? (fallback != null ? fallback : "") : v.trim();
    }

    static final class ChunkSpec {
        final String summary;
        final String keyword;
        final String file;

        ChunkSpec(String summary, String keyword, String file) {
            this.summary = summary != null ? summary.trim() : "";
            this.keyword = keyword;
            this.file = file;
        }
    }
}
