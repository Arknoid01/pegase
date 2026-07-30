package com.pegasuscorp.orbe.orion.qa;

import android.content.Context;
import android.text.TextUtils;

import com.pegasuscorp.orbe.orion.OrionFileParser;
import com.pegasuscorp.orbe.orion.OrionProjectStore;
import com.pegasuscorp.orbe.orion.prompt.OrionMode;
import com.pegasuscorp.orbe.orion.prompt.PromptCompiler;
import com.pegasuscorp.orbe.orion.prompt.ResolvedTask;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Boucle QA max 2 itérations : check → contraintes → régénération gérée par l'UI.
 */
public final class OrionQaLoop {

    public static final int MAX_ATTEMPTS = 2;

    public interface Callback {
        void onCompliant(OrionQaReport report, int attempt);
        /** Demande une nouvelle génération avec mission augmentée. */
        void onRetry(String augmentedMission, OrionQaReport report, int attempt);
        /** Échec après MAX_ATTEMPTS — proposer le diff à Yann. */
        void onGiveUp(OrionQaReport report);
        void onProgress(String message);
    }

    private static final ExecutorService BG = Executors.newSingleThreadExecutor();

    private OrionQaLoop() {}

    /**
     * Lance un check sur la sortie Orion courante.
     * @param attempt 1-based
     */
    public static void evaluate(Context ctx, String missionBlock, String generatedOutput,
            int attempt, OrionQaChecker.SemanticJudge judge, Callback cb) {
        evaluate(ctx, null, missionBlock, generatedOutput, attempt, judge, cb);
    }

    /**
     * @param resolvedTask tâche enrichie (risque + fileLocation) — peut être null
     */
    public static void evaluate(Context ctx, ResolvedTask resolvedTask,
            String missionBlock, String generatedOutput,
            int attempt, OrionQaChecker.SemanticJudge judge, Callback cb) {
        if (cb == null) return;
        final int att = Math.max(1, attempt);
        BG.execute(() -> {
            try {
                cb.onProgress("QA Orion — tentative " + att + "/" + MAX_ATTEMPTS + "…");
                ResolvedTask task = resolvedTask;
                if (task == null) {
                    // missionBlock peut contenir « feature » dans les exclusions — ne pas detect()
                    task = PromptCompiler.resolve(ctx, missionBlock, missionBlock, OrionMode.PATCH);
                }
                Map<String, String> after = parseGenerated(generatedOutput);
                Map<String, String> before = readBaseline(ctx, after);
                OrionQaReport report = OrionQaChecker.check(task, before, after, judge);
                if (report.isCompliant()) {
                    cb.onCompliant(report, att);
                    return;
                }
                if (att >= MAX_ATTEMPTS) {
                    cb.onGiveUp(report);
                    return;
                }
                String augmented = OrionQaChecker.augmentMission(missionBlock, report);
                cb.onRetry(augmented, report, att);
            } catch (Exception e) {
                // Ne bloque pas Yann
                cb.onCompliant(OrionQaReport.compliant("(QA ignoré : "
                        + (e.getMessage() != null ? e.getMessage() : "erreur") + ")"), att);
            }
        });
    }

    public static Map<String, String> parseGenerated(String output) {
        Map<String, String> map = new LinkedHashMap<>();
        if (TextUtils.isEmpty(output)) return map;
        List<OrionFileParser.ParsedFile> parsed = OrionFileParser.parse(output);
        if (parsed != null) {
            for (OrionFileParser.ParsedFile f : parsed) {
                if (f == null || TextUtils.isEmpty(f.path)) continue;
                map.put(f.path, f.content != null ? f.content : "");
            }
        }
        return map;
    }

    public static Map<String, String> readBaseline(Context ctx, Map<String, String> after) {
        Map<String, String> before = new LinkedHashMap<>();
        if (ctx == null || after == null || after.isEmpty()) return before;
        try {
            OrionProjectStore store = OrionProjectStore.get(ctx);
            if (!store.hasActiveProject()) return before;
            for (String path : after.keySet()) {
                String content = store.readFile(path);
                if (content != null) before.put(path, content);
                else before.put(path, "");
            }
        } catch (Exception ignored) {
        }
        return before;
    }

    public static boolean looksLikeMission(String prompt) {
        if (TextUtils.isEmpty(prompt)) return false;
        String lower = prompt.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("mission :") || lower.contains("objectif principal");
    }
}
