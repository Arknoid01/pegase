package com.pegasuscorp.orbe.orion.prompt;

import android.content.Context;
import android.text.TextUtils;

import com.pegasuscorp.orbe.orion.OrionProjectStore;
import com.pegasuscorp.orbe.orion.TaskComplexity;
import com.pegasuscorp.orbe.orion.TaskComplexityEstimator;
import com.pegasuscorp.orbe.orion.TaskRisk;
import com.pegasuscorp.orbe.orion.search.FileLocation;
import com.pegasuscorp.orbe.orion.search.OrionCodeIndexService;
import com.pegasuscorp.orbe.orion.search.OrionCodeIndexer;
import com.pegasuscorp.orbe.orion.search.OrionFileSearcher;
import com.pegasuscorp.orbe.rag.EmbeddingEngine;
import com.pegasuscorp.orbe.rag.VectorStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Formate un {@link ResolvedTask} en mission Orion stricte.
 * N'invente aucune précision manquante.
 */
public final class PromptCompiler {

    private PromptCompiler() {}

    public static String compile(ResolvedTask task) {
        if (task == null) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("Mission : ").append(nz(task.mission, "correction ciblée")).append("\n\n");
        if (!TextUtils.isEmpty(task.context)) {
            sb.append("Contexte :\n");
            for (String line : splitLines(task.context)) {
                sb.append(bullet(line)).append('\n');
            }
            sb.append('\n');
        }
        if (!TextUtils.isEmpty(task.extractedKeyword)) {
            sb.append("Mot-clé ciblé :\n")
                    .append(task.extractedKeyword.trim()).append("\n\n");
        }
        sb.append("Objectif principal :\n")
                .append(nz(task.objective, "(à préciser)")).append("\n\n");
        if (!task.actions.isEmpty()) {
            sb.append("À faire :\n");
            for (String a : task.actions) sb.append(bullet(a)).append('\n');
            sb.append('\n');
        }
        sb.append("Ne pas toucher / Contraintes :\n");
        if (task.exclusions.isEmpty()) {
            if (task.mode == OrionMode.FEATURE) {
                sb.append("- Implémente la fonctionnalité demandée. Plusieurs fichiers autorisés.\n");
                sb.append("- N'ajoute rien qui ne soit pas demandé ou prévu dans les documents "
                        + "chargés.\n");
            } else {
                sb.append("- Aucun refactoring ; pas de nouvelle fonctionnalité ; patch minimal.\n");
            }
        } else {
            for (String e : task.exclusions) sb.append(bullet(e)).append('\n');
        }
        sb.append('\n');
        if (!task.investigate.isEmpty()) {
            sb.append("À investiguer :\n");
            for (String i : task.investigate) sb.append(bullet(i)).append('\n');
            sb.append('\n');
        }
        if (!task.assumptions.isEmpty()) {
            sb.append("Hypothèses retenues :\n");
            for (String a : task.assumptions) sb.append(bullet(a)).append('\n');
            sb.append('\n');
        }
        sb.append("Validation :\n");
        if (task.validation.isEmpty()) {
            sb.append("- Comportement demandé vérifiable ; rien d'autre modifié.\n");
        } else {
            for (String v : task.validation) sb.append(bullet(v)).append('\n');
        }
        return sb.toString().trim();
    }

    /**
     * Parse + localisation + complexité/risque.
     * Mode : {@link OrionMode#detect(String)} sur {@code rawInput} seulement
     * (jamais sur une mission compilée / réécrite). Si {@code rawInput} vide
     * ou ressemble à un bloc Mission → PATCH.
     */
    public static ResolvedTask resolve(Context ctx, String missionBlock, String rawInput) {
        OrionMode mode;
        if (TextUtils.isEmpty(rawInput) || looksLikeCompiledMission(rawInput)) {
            // Sortie rewriter / bloc Mission ≠ demande utilisateur
            mode = OrionMode.PATCH;
        } else {
            mode = OrionMode.detect(rawInput);
        }
        return resolveWithMode(ctx, missionBlock, rawInput, mode);
    }

    /**
     * True si le texte est une mission enrichie (PromptCompiler / rewriter),
     * pas la saisie brute de Yann.
     */
    public static boolean looksLikeCompiledMission(String text) {
        if (TextUtils.isEmpty(text)) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        boolean hasMission = lower.contains("mission :") || lower.contains("mission:");
        if (!hasMission) return false;
        return lower.contains("objectif")
                || lower.contains("ne pas toucher")
                || lower.contains("à faire")
                || lower.contains("a faire")
                || lower.contains("mot-clé ciblé")
                || lower.contains("mot-cle cible")
                || lower.contains("validation");
    }

    /**
     * Chemin dérivé (chunk / plan / QA) : mode imposé, aucune détection.
     */
    public static ResolvedTask resolve(Context ctx, String missionBlock,
            String stepSummary, OrionMode mode) {
        OrionMode m = mode != null ? mode : OrionMode.PATCH;
        return resolveWithMode(ctx, missionBlock, stepSummary, m);
    }

    private static ResolvedTask resolveWithMode(Context ctx, String missionBlock,
            String demandOrSummary, OrionMode mode) {
        ResolvedTask parsed = parseMissionBlock(missionBlock);
        String source = TextUtils.isEmpty(demandOrSummary) ? missionBlock : demandOrSummary;
        FileLocation location = findFileLocation(ctx, parsed);
        if (location == null && !TextUtils.isEmpty(source)) {
            location = findFileLocationForDemand(ctx, source);
        }
        ResolvedTask withDemand = ResolvedTask.builder()
                .from(parsed)
                .rawInput(source)
                .mode(mode)
                .build();
        TaskComplexityEstimator estimator = new TaskComplexityEstimator();
        TaskComplexity complexity = estimator.estimate(withDemand);
        TaskRisk risk = estimator.assessRisk(withDemand, location, complexity, source);
        return ResolvedTask.builder()
                .from(withDemand)
                .complexity(complexity)
                .risk(risk)
                .fileLocation(location)
                .mode(mode)
                .build();
    }

    /**
     * Prompt planification GPT-120 (Phase 6 sandwich) — snippet FileSearcher inclus.
     */
    public static String buildPlanningPrompt(ResolvedTask task) {
        if (task == null) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("Mission : ").append(nz(task.rawInput, task.mission)).append("\n\n");
        if (task.fileLocation != null) {
            sb.append("=== Code concerné ===\n");
            sb.append(task.fileLocation.toPromptBlock()).append("\n");
            sb.append("Planifie en tenant compte de ce code exact.\n");
            sb.append("Identifie précisément ce qui doit changer.\n\n");
        }
        String contextSummary = buildContextSummary(task);
        if (!TextUtils.isEmpty(contextSummary)) {
            sb.append("Contexte : ").append(contextSummary).append("\n");
        }
        sb.append("Patch minimal. Ne pas toucher au reste.\n");
        sb.append("Identifie : Objectif / À faire / Ne pas toucher / Risques\n");
        return sb.toString().trim();
    }

    public static FileLocation findFileLocation(Context ctx, ResolvedTask task) {
        if (ctx == null || task == null || TextUtils.isEmpty(task.extractedKeyword)) return null;
        return findByKeyword(ctx, task.extractedKeyword);
    }

    /** Recherche préliminaire avant extraction keyword GPT-120. */
    public static FileLocation findFileLocationForDemand(Context ctx, String demand) {
        if (ctx == null || TextUtils.isEmpty(demand)) return null;
        return findByKeyword(ctx, demand.trim());
    }

    private static FileLocation findByKeyword(Context ctx, String keyword) {
        try {
            OrionProjectStore store = OrionProjectStore.get(ctx);
            String activeProject = store.getActiveProject();
            if (TextUtils.isEmpty(activeProject)) return null;
            OrionCodeIndexer codeIndexer =
                    OrionCodeIndexService.get().indexerFor(ctx, activeProject);
            OrionFileSearcher searcher = new OrionFileSearcher(
                    store, EmbeddingEngine.get(ctx), new VectorStore(ctx), codeIndexer);
            Optional<FileLocation> loc = searcher.find(activeProject, keyword);
            return loc.orElse(null);
        } catch (Exception ignored) {
            return null;
        }
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

    /** Parse un bloc Mission déjà produit (LLM ou édité). */
    public static ResolvedTask parseMissionBlock(String raw) {
        if (TextUtils.isEmpty(raw)) return ResolvedTask.builder().build();
        String text = raw.trim();
        ResolvedTask.Builder b = ResolvedTask.builder();
        String section = "";
        StringBuilder buf = new StringBuilder();
        for (String line : text.split("\n")) {
            String t = line.trim();
            String lower = t.toLowerCase(Locale.ROOT);
            if (lower.startsWith("mission :") || lower.startsWith("mission:")) {
                flush(b, section, buf);
                section = "mission";
                buf.setLength(0);
                buf.append(afterColon(t));
                continue;
            }
            if (lower.startsWith("contexte")) {
                flush(b, section, buf);
                section = "context";
                buf.setLength(0);
                continue;
            }
            if (lower.startsWith("objectif")) {
                flush(b, section, buf);
                section = "objective";
                buf.setLength(0);
                String rest = afterColon(t);
                if (!rest.isEmpty()) buf.append(rest);
                continue;
            }
            if (lower.startsWith("mot-cle cible")
                    || lower.startsWith("mot clé ciblé")
                    || lower.startsWith("mot-cle cible")
                    || lower.startsWith("mot-clé ciblé")
                    || lower.startsWith("keyword")) {
                flush(b, section, buf);
                section = "keyword";
                buf.setLength(0);
                String rest = afterColon(t);
                if (!rest.isEmpty()) buf.append(rest);
                continue;
            }
            if (lower.startsWith("à faire") || lower.startsWith("a faire")) {
                flush(b, section, buf);
                section = "actions";
                buf.setLength(0);
                continue;
            }
            if (lower.startsWith("ne pas toucher") || lower.startsWith("contraintes")) {
                flush(b, section, buf);
                section = "exclusions";
                buf.setLength(0);
                continue;
            }
            if (lower.startsWith("à investiguer") || lower.startsWith("a investiguer")) {
                flush(b, section, buf);
                section = "investigate";
                buf.setLength(0);
                continue;
            }
            if (lower.startsWith("hypothèses") || lower.startsWith("hypotheses")) {
                flush(b, section, buf);
                section = "assumptions";
                buf.setLength(0);
                continue;
            }
            if (lower.startsWith("validation")) {
                flush(b, section, buf);
                section = "validation";
                buf.setLength(0);
                continue;
            }
            if (buf.length() > 0) buf.append('\n');
            buf.append(t);
        }
        flush(b, section, buf);
        return b.build();
    }

    private static void flush(ResolvedTask.Builder b, String section, StringBuilder buf) {
        String v = buf.toString().trim();
        if (v.isEmpty() || section.isEmpty()) return;
        switch (section) {
            case "mission":
                b.mission(v.replace("\n", " ").trim());
                break;
            case "context":
                b.context(v);
                break;
            case "objective":
                b.objective(v);
                break;
            case "keyword":
                b.keyword(v.replace("\n", " ").trim());
                break;
            case "actions":
                for (String line : splitBullets(v)) b.action(line);
                break;
            case "exclusions":
                for (String line : splitBullets(v)) b.exclusion(line);
                break;
            case "investigate":
                for (String line : splitBullets(v)) b.investigate(line);
                break;
            case "assumptions":
                for (String line : splitBullets(v)) b.assumption(line);
                break;
            case "validation":
                for (String line : splitBullets(v)) b.validation(line);
                break;
            default:
                break;
        }
    }

    private static List<String> splitBullets(String v) {
        List<String> out = new ArrayList<>();
        for (String line : v.split("\n")) {
            String t = line.trim();
            if (t.startsWith("-")) t = t.substring(1).trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private static List<String> splitLines(String v) {
        List<String> out = new ArrayList<>();
        for (String line : v.split("\n")) {
            String t = line.trim();
            if (t.startsWith("-")) t = t.substring(1).trim();
            if (!t.isEmpty()) out.add(t);
        }
        if (out.isEmpty() && !TextUtils.isEmpty(v)) out.add(v.trim());
        return out;
    }

    private static String bullet(String s) {
        String t = s.trim();
        if (t.startsWith("-")) return t;
        return "- " + t;
    }

    private static String afterColon(String line) {
        int i = line.indexOf(':');
        if (i < 0) return "";
        return line.substring(i + 1).trim();
    }

    private static String nz(String v, String fallback) {
        return TextUtils.isEmpty(v) ? fallback : v.trim();
    }
}
