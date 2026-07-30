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
import java.util.Locale;
import java.util.StringJoiner;

/**
 * Construit un plan complet pour une mission MASSIVE — validé avant exécution.
 * Les missions LARGE passent par {@link TaskChunker} (découpage à l'exécution).
 */
public final class PlanBuilder {

    private static final int MAX_STEPS = 8;

    /** Appel LLM éphémère (GPT-120 / Pégase). */
    public interface PlanPlanner {
        String plan(String prompt) throws Exception;
    }

    private final OrionFileSearcher searcher;
    private final OrionProjectStore projectStore;

    public PlanBuilder(OrionFileSearcher searcher, OrionProjectStore projectStore) {
        this.searcher = searcher;
        this.projectStore = projectStore;
    }

    public static PlanBuilder create(Context ctx) {
        OrionProjectStore store = OrionProjectStore.get(ctx);
        String active = store.getActiveProject();
        OrionCodeIndexer codeIndexer = TextUtils.isEmpty(active)
                ? null
                : OrionCodeIndexService.get().indexerFor(ctx, active);
        try {
            OrionFileSearcher searcher = new OrionFileSearcher(
                    store, EmbeddingEngine.get(ctx), new VectorStore(ctx), codeIndexer);
            return new PlanBuilder(searcher, store);
        } catch (Exception e) {
            OrionFileSearcher searcher = new OrionFileSearcher(store, new VectorStore(ctx))
                    .withCodeIndexer(codeIndexer);
            return new PlanBuilder(searcher, store);
        }
    }

    public ExecutionPlan build(Context ctx, ResolvedTask task, PlanPlanner planner)
            throws Exception {
        if (task == null) {
            return new ExecutionPlan("Mission", Collections.emptyList(), TaskRisk.MEDIUM);
        }
        String json = planner.plan(buildPlanPrompt(task));
        List<PlanStep> steps = parsePlanSteps(json, task);
        enrichSteps(steps);
        TaskRisk globalRisk = computeGlobalRisk(steps);
        return new ExecutionPlan(extractTitle(task), steps, globalRisk);
    }

    /** Convertit un plan approuvé en {@link TaskChunk} pour {@link OrionChunkSession}. */
    public List<TaskChunk> toTaskChunks(Context ctx, ExecutionPlan plan, ResolvedTask parent) {
        if (plan == null || plan.steps.isEmpty()) return Collections.emptyList();
        int total = plan.steps.size();
        List<TaskChunk> chunks = new ArrayList<>();
        for (PlanStep step : plan.steps) {
            ResolvedTask chunkTask = buildChunkTask(ctx, parent, step, total);
            chunks.add(new TaskChunk(step.index, total, chunkTask, step.summary));
        }
        return chunks;
    }

    static List<PlanStep> parsePlanSteps(String json, ResolvedTask task) {
        String fallbackSummary = task != null && !TextUtils.isEmpty(task.rawInput)
                ? task.rawInput
                : (task != null ? task.mission : "Mission");
        try {
            JSONArray arr = new JSONArray(TaskChunker.extractJsonArray(json));
            List<PlanStep> steps = new ArrayList<>();
            int limit = Math.min(arr.length(), MAX_STEPS);
            for (int i = 0; i < limit; i++) {
                JSONObject o = arr.getJSONObject(i);
                steps.add(new PlanStep(
                        i + 1,
                        o.optString("summary", "Étape " + (i + 1)),
                        o.optString("file", null),
                        o.optString("keyword", null),
                        parseRisk(o.optString("risk", "MEDIUM"))));
            }
            if (!steps.isEmpty()) return steps;
        } catch (Exception e) {
            Trace.orionPlanError(e.getMessage());
        }
        return List.of(new PlanStep(1, fallbackSummary, null, null, TaskRisk.MEDIUM));
    }

    static TaskRisk parseRisk(String raw) {
        if (raw == null) return TaskRisk.MEDIUM;
        try {
            return TaskRisk.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return TaskRisk.MEDIUM;
        }
    }

    static TaskRisk computeGlobalRisk(List<PlanStep> steps) {
        TaskRisk max = TaskRisk.LOW;
        for (PlanStep s : steps) {
            if (s.risk != null && s.risk.ordinal() > max.ordinal()) {
                max = s.risk;
            }
        }
        return max;
    }

    static String buildPlanPrompt(ResolvedTask task) {
        StringJoiner critical = new StringJoiner(", ");
        for (String f : OrionConfig.CRITICAL_FILES) critical.add(f);
        return "Tu es architecte senior web (HTML/CSS/JS).\n"
                + "Construis un plan d'exécution pour cette mission complexe.\n\n"
                + "Règles :\n"
                + "- Maximum 8 étapes\n"
                + "- Chaque étape : 1 fichier, 1 objectif précis\n"
                + "- Ordre : du plus indépendant au plus dépendant\n"
                + "- Identifier les fichiers à risque\n"
                + "- Format JSON strict :\n"
                + "  [{\"summary\":\"...\",\"file\":\"...\",\"keyword\":\"...\","
                + "\"risk\":\"LOW|MEDIUM|HIGH|CRITICAL\"}]\n\n"
                + "Mission : " + nz(task.rawInput, task.mission) + "\n"
                + "Fichiers CRITICAL connus : " + critical + "\n"
                + "Contexte projet : " + buildContextSummary(task);
    }

    private void enrichSteps(List<PlanStep> steps) {
        String projectName = projectStore != null ? projectStore.getActiveProject() : "";
        for (PlanStep step : steps) {
            if (!TextUtils.isEmpty(step.targetFile)
                    && OrionConfig.isCriticalFilename(step.targetFile)) {
                step.risk = TaskRisk.CRITICAL;
            }
            if (!TextUtils.isEmpty(projectName) && !TextUtils.isEmpty(step.keyword)) {
                searcher.find(projectName, step.keyword).ifPresent(loc -> {
                    if (!TextUtils.isEmpty(loc.filename)) {
                        step.targetFile = loc.filename;
                        if (OrionConfig.isCriticalFilename(loc.filename)) {
                            step.risk = TaskRisk.CRITICAL;
                        }
                    }
                });
            }
            if (TextUtils.isEmpty(step.targetFile) && !TextUtils.isEmpty(step.keyword)
                    && step.keyword.contains(".")) {
                step.targetFile = step.keyword;
                if (OrionConfig.isCriticalFilename(step.targetFile)) {
                    step.risk = TaskRisk.CRITICAL;
                }
            }
        }
    }

    private ResolvedTask buildChunkTask(Context ctx, ResolvedTask parent,
            PlanStep step, int total) {
        ResolvedTask.Builder b = ResolvedTask.builder()
                .mission("Étape " + step.index + "/" + total)
                .objective(step.summary)
                .rawInput(step.summary)
                .action(step.summary)
                .exclusion("Ne modifier qu'un seul fichier pour cette étape")
                .validation("Patch minimal — étape " + step.index + " uniquement")
                .complexity(TaskComplexity.SIMPLE)
                .risk(step.risk);
        if (!TextUtils.isEmpty(step.keyword)) {
            b.keyword(step.keyword);
        }
        if (parent != null && !TextUtils.isEmpty(parent.context)) {
            b.context(parent.context);
        }
        if (!TextUtils.isEmpty(step.targetFile)) {
            String ctxLine = "Fichier ciblé : " + step.targetFile;
            String existing = parent != null ? nz(parent.context, "") : "";
            b.context(TextUtils.isEmpty(existing) ? ctxLine : existing + "\n" + ctxLine);
        }
        OrionMode parentMode = parent != null && parent.mode != null
                ? parent.mode
                : OrionMode.PATCH;
        b.mode(parentMode);
        String compiled = PromptCompiler.compile(b.build());
        ResolvedTask resolved = PromptCompiler.resolve(ctx, compiled, step.summary, parentMode);
        return ResolvedTask.builder()
                .from(resolved)
                .complexity(TaskComplexity.SIMPLE)
                .risk(step.risk)
                .mode(parentMode)
                .build();
    }

    private static String extractTitle(ResolvedTask task) {
        String raw = nz(task.rawInput, task.mission);
        if (raw.length() > 60) return raw.substring(0, 57) + "...";
        return raw;
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

    private static String nz(String v, String fallback) {
        return TextUtils.isEmpty(v) ? (fallback != null ? fallback : "") : v.trim();
    }
}
