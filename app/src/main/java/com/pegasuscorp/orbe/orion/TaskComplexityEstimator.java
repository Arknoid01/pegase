package com.pegasuscorp.orbe.orion;

import android.text.TextUtils;

import com.pegasuscorp.orbe.orion.prompt.ResolvedTask;
import com.pegasuscorp.orbe.orion.search.FileLocation;

import java.util.HashSet;
import java.util.Set;

/**
 * Estime complexité et risque d'une {@link ResolvedTask} déjà compilée.
 */
public final class TaskComplexityEstimator {

    // fold() transforme les apostrophes en espaces : des marqueurs courts
    // ("toute l'", "tout le ", "complètement"…) matchent du français courant.
    // Ne garder que des formulations explicites / non ambiguës.
    private static final String[] MASSIVE_MARKERS = {
            "refais tout le projet",
            "refais toute l'application",
            "restructure l'architecture",
            "réécris entièrement",
            "réorganise tout le projet"
    };

    public TaskComplexityEstimator() {}

    public TaskComplexity estimate(ResolvedTask task) {
        return estimate(task, demandRaw(task));
    }

    public TaskComplexity estimate(ResolvedTask task, String rawInput) {
        if (task == null) return TaskComplexity.MEDIUM;
        int itemCount = task.actions != null ? task.actions.size() : 0;
        // Priorité à la demande (rawInput/mission) : ignore un 2ᵉ arg enrichi (contexte, etc.).
        String raw = demandRaw(task);
        if (TextUtils.isEmpty(raw)) raw = rawInput == null ? "" : rawInput;
        if (isGreenfield(raw)) {
            // Slice Bureau : ne pas monter en LARGE/MASSIVE juste à cause du plan chargé
            if (itemCount > 5 || countFileHints(raw) >= 4) return TaskComplexity.MEDIUM;
            return TaskComplexity.SIMPLE;
        }
        if (containsAny(raw, MASSIVE_MARKERS)) return TaskComplexity.MASSIVE;
        if (countFileHints(raw) >= 3 || itemCount > 8) return TaskComplexity.LARGE;
        if (countFileHints(raw) >= 2 || itemCount > 5) return TaskComplexity.MEDIUM;
        return TaskComplexity.SIMPLE;
    }

    public TaskRisk assessRisk(ResolvedTask task, FileLocation location) {
        TaskComplexity complexity = task != null ? task.complexity : TaskComplexity.MEDIUM;
        return assessRisk(task, location, complexity, combinedRaw(task));
    }

    public TaskRisk assessRisk(ResolvedTask task, FileLocation location,
            TaskComplexity complexity, String rawInput) {
        String raw = rawInput == null ? combinedRaw(task) : rawInput;
        if (location != null && OrionConfig.isCriticalTarget(location.filename, raw)) {
            return TaskRisk.CRITICAL;
        }
        if (OrionConfig.isCriticalTarget(null, raw)) return TaskRisk.CRITICAL;
        if (complexity == TaskComplexity.LARGE || complexity == TaskComplexity.MASSIVE) {
            return TaskRisk.HIGH;
        }
        if (complexity == TaskComplexity.MEDIUM) return TaskRisk.MEDIUM;
        return TaskRisk.LOW;
    }

    /** Demande utilisateur seule — pas le contexte enrichi (fichiers, actions). */
    private static String demandRaw(ResolvedTask task) {
        if (task == null) return "";
        if (!TextUtils.isEmpty(task.rawInput)) return task.rawInput;
        if (!TextUtils.isEmpty(task.mission)) return task.mission;
        return "";
    }

    private static String combinedRaw(ResolvedTask task) {
        if (task == null) return "";
        StringBuilder sb = new StringBuilder();
        if (!TextUtils.isEmpty(task.rawInput)) sb.append(task.rawInput).append(' ');
        if (!TextUtils.isEmpty(task.mission)) sb.append(task.mission).append(' ');
        if (!TextUtils.isEmpty(task.objective)) sb.append(task.objective).append(' ');
        if (!TextUtils.isEmpty(task.context)) sb.append(task.context).append(' ');
        if (task.actions != null) {
            for (String a : task.actions) {
                if (!TextUtils.isEmpty(a)) sb.append(a).append(' ');
            }
        }
        return sb.toString().trim();
    }

    private static int countFileHints(String raw) {
        if (TextUtils.isEmpty(raw)) return 0;
        Set<String> distinct = new HashSet<>();
        String fold = fold(raw);
        for (String token : fold.split("\\s+")) {
            if (token.endsWith(".java") || token.endsWith(".js") || token.endsWith(".html")
                    || token.endsWith(".css") || token.endsWith(".kt") || token.endsWith(".xml")) {
                distinct.add(token);
            }
        }
        return distinct.size();
    }

    private static boolean isGreenfield(String raw) {
        if (TextUtils.isEmpty(raw)) return false;
        String f = fold(raw);
        return f.contains("mode greenfield")
                || f.contains("greenfield")
                || f.contains("creation depuis bureau")
                || f.contains("premiere tache")
                || f.contains("un slice minimal")
                || f.contains("implemente le plan")
                || f.contains("taches non coche");
    }

    static boolean containsAny(String raw, String... needles) {
        if (TextUtils.isEmpty(raw) || needles == null) return false;
        String fold = fold(raw);
        for (String n : needles) {
            if (n != null && fold.contains(fold(n))) return true;
        }
        return false;
    }

    /** Délègue à {@link com.pegasuscorp.orbe.orion.prompt.TextFold}. */
    static String fold(String text) {
        return com.pegasuscorp.orbe.orion.prompt.TextFold.fold(text);
    }
}
