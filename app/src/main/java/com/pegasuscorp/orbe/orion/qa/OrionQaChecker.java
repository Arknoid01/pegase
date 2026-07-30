package com.pegasuscorp.orbe.orion.qa;

import android.text.TextUtils;

import com.pegasuscorp.orbe.diag.Trace;
import com.pegasuscorp.orbe.orion.OrionTextDiff;
import com.pegasuscorp.orbe.orion.TaskRisk;
import com.pegasuscorp.orbe.orion.prompt.OrionMode;
import com.pegasuscorp.orbe.orion.prompt.PromptCompiler;
import com.pegasuscorp.orbe.orion.prompt.ResolvedTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * QA Orion : structure (diff vs mission) + sémantique (LLM externe via callback).
 * Phase 6 — Reasoning Sandwich : routage par {@link TaskRisk}.
 */
public final class OrionQaChecker {

    public interface SemanticJudge {
        /** @return texte brut CONFORME / NON_CONFORME… ou null si skip */
        String judge(String missionBlock, String diffSummary);
    }

    private OrionQaChecker() {}

    /**
     * @param before path → contenu projet avant génération (peut être vide)
     * @param after  path → contenu généré
     */
    public static OrionQaReport check(ResolvedTask task,
            Map<String, String> before, Map<String, String> after,
            SemanticJudge semantic) {
        ResolvedTask t = task != null ? task : ResolvedTask.builder().build();
        Map<String, String> b = before != null ? before : new LinkedHashMap<>();
        Map<String, String> a = after != null ? after : new LinkedHashMap<>();

        String diffSummary = buildDiffSummary(b, a);
        TaskRisk risk = t.risk != null ? t.risk : TaskRisk.MEDIUM;

        OrionQaReport structural = checkStructural(t, b, a, diffSummary);
        if (!structural.isCompliant()) {
            Trace.orionSandwich("verify", snippetTokens(t), t.fileLocation != null,
                    "NON_CONFORME");
            return structural;
        }

        // LOW → structurel seul (0 LLM)
        if (risk == TaskRisk.LOW) {
            Trace.orionSandwich("verify", snippetTokens(t), t.fileLocation != null,
                    "CONFORME");
            return OrionQaReport.compliant(diffSummary);
        }

        if (semantic == null || TextUtils.isEmpty(diffSummary)
                || "Aucun changement.".equals(diffSummary)) {
            Trace.orionSandwich("verify", snippetTokens(t), t.fileLocation != null,
                    "CONFORME");
            return OrionQaReport.compliant(diffSummary);
        }

        boolean full = risk == TaskRisk.HIGH || risk == TaskRisk.CRITICAL;
        String verifyPrompt = buildVerificationPrompt(t, clip(diffSummary, 6000), full);
        String raw;
        try {
            // missionBlock = prompt de vérif complet ; diff déjà inclus
            raw = semantic.judge(verifyPrompt, "");
        } catch (Exception e) {
            Trace.orionSandwich("verify", snippetTokens(t), t.fileLocation != null,
                    "CONFORME");
            return OrionQaReport.compliant(diffSummary);
        }
        OrionQaReport report = parseSemantic(raw, diffSummary);
        Trace.orionSandwich("verify", snippetTokens(t), t.fileLocation != null,
                report.isCompliant() ? "CONFORME" : "NON_CONFORME");
        return report;
    }

    static OrionQaReport checkStructural(ResolvedTask task,
            Map<String, String> before, Map<String, String> after, String diffSummary) {
        List<String> extras = new ArrayList<>();
        List<String> problems = new ArrayList<>();

        String missionFold = fold(task.mission + " " + task.objective
                + " " + join(task.actions)
                + " " + nz(task.rawInput, ""));
        boolean greenfield = looksLikeGreenfieldMission(missionFold);
        // FEATURE = extension légitime multi-fichiers (même exemption que greenfield).
        boolean multiFileExempt = greenfield || task.mode == OrionMode.FEATURE;

        // Hors scope : fichier ciblé connu mais d'autres fichiers touchés
        // (sauf greenfield / FEATURE : scaffold ou feature multi-fichiers attendu)
        if (!multiFileExempt
                && task.fileLocation != null
                && !TextUtils.isEmpty(task.fileLocation.filename)) {
            List<String> extraFiles = extractExtraFiles(before, after, task.fileLocation.filename);
            if (!extraFiles.isEmpty()) {
                problems.add("Orion a modifié un fichier hors scope : "
                        + String.join(", ", extraFiles));
                extras.add("Ne modifier que " + task.fileLocation.filename);
            }
        }

        // Exclusions : si un mot-clé d'exclusion apparaît dans les lignes ajoutées/supprimées
        List<String> exclusionKeys = extractKeywords(task.exclusions);
        String changedBlob = changedLinesBlob(before, after).toLowerCase(Locale.ROOT);

        for (String key : exclusionKeys) {
            if (key.length() < 3) continue;
            if (changedBlob.contains(key.toLowerCase(Locale.ROOT))) {
                problems.add("Changement détecté sur « " + key
                        + " » alors que c'était exclu.");
                extras.add("Ne pas toucher : " + key);
            }
        }

        // Heuristique CSS / style si la mission parle de particules / logique sans UI
        boolean logicFocus = !multiFileExempt && (missionFold.contains("particule")
                || missionFold.contains("particle")
                || missionFold.contains("count")
                || missionFold.contains("densite")
                || missionFold.contains("fonction")
                || missionFold.contains("bug")
                || missionFold.contains("lag"));
        boolean styleTouched = changedBlob.contains("background")
                || changedBlob.contains("color:")
                || changedBlob.contains("font-")
                || changedBlob.contains("margin:")
                || changedBlob.contains("padding:");
        if (logicFocus && styleTouched
                && !missionFold.contains("style")
                && !missionFold.contains("css")
                && !missionFold.contains("couleur")
                && !missionFold.contains("fond")
                && !missionFold.contains("design")) {
            problems.add("La mission est logique/particules mais le CSS/style a changé.");
            extras.add("Ne pas toucher : CSS, background, couleurs, styles existants");
        }

        // Fichiers hors scope : beaucoup de fichiers touchés pour une retouche simple
        int touched = 0;
        for (String path : after.keySet()) {
            String old = before.get(path);
            String neu = after.get(path);
            if (neu == null) continue;
            if (old == null || !old.equals(neu)) touched++;
        }
        if (!multiFileExempt && logicFocus && touched >= 3) {
            problems.add(touched + " fichiers modifiés — trop large pour une retouche ciblée.");
            extras.add("Ne modifier qu'un seul fichier si possible (celui qui contient la logique)");
        }

        if (problems.isEmpty()) {
            return OrionQaReport.compliant(diffSummary);
        }
        StringBuilder reason = new StringBuilder();
        for (int i = 0; i < problems.size(); i++) {
            if (i > 0) reason.append(' ');
            reason.append(problems.get(i));
        }
        return OrionQaReport.nonCompliant(reason.toString(), extras, diffSummary, true, false);
    }

    /** Prompt vérification GPT-120 — snippet original + diff (Phase 6). */
    public static String buildVerificationPrompt(ResolvedTask task, String diffSummary,
            boolean full) {
        ResolvedTask t = task != null ? task : ResolvedTask.builder().build();
        StringBuilder sb = new StringBuilder();
        sb.append("Tu es le QA Pégase pour Orion.\n");
        if (full) {
            sb.append("Vérification approfondie (HIGH/CRITICAL).\n");
        } else {
            sb.append("Vérification courte (MEDIUM).\n");
        }
        sb.append("=== Mission ===\n")
                .append(nz(t.rawInput, PromptCompiler.compile(t))).append("\n\n");

        if (t.fileLocation != null) {
            sb.append("=== Code original ===\n")
                    .append(t.fileLocation.toPromptBlock()).append("\n\n");
        }

        sb.append("=== Diff généré par Orion ===\n")
                .append(diffSummary != null ? diffSummary : "").append("\n\n");

        String missionBlob = fold(nz(t.rawInput, "") + " " + nz(t.mission, "")
                + " " + nz(t.objective, ""));
        boolean greenfield = looksLikeGreenfieldMission(missionBlob);
        sb.append("Ce diff est-il CONFORME à la mission ?\n")
                .append("Vérifie :\n");
        if (greenfield) {
            sb.append("1. Le slice demandé est couvert (pas besoin de tout le plan)\n")
                    .append("2. Plusieurs petits fichiers OK pour un scaffold\n")
                    .append("3. Pas de Java/Kotlin si HTML/CSS/JS était demandé\n")
                    .append("4. Pas de refactor hors slice\n");
        } else {
            sb.append("1. Seule la partie demandée est modifiée\n")
                    .append("2. Le reste du fichier est intact\n")
                    .append("3. Pas de régression visible\n");
            if (full || t.risk == TaskRisk.CRITICAL) {
                sb.append("4. Si fichier CRITICAL → patch minimal absolu\n");
            }
        }
        sb.append("\nRéponds STRICTEMENT :\n")
                .append("CONFORME\n")
                .append("ou\n")
                .append("NON_CONFORME : <raison courte>\n")
                .append("Ne pas toucher : <élément modifié à tort>\n");
        return sb.toString();
    }

    /** Compat — sans snippet / sans risque. */
    public static String buildSemanticPrompt(String missionBlock, String diffSummary) {
        return buildVerificationPrompt(
                ResolvedTask.builder().rawInput(missionBlock).mission(missionBlock).build(),
                diffSummary, true);
    }

    static OrionQaReport parseSemantic(String raw, String diffSummary) {
        if (TextUtils.isEmpty(raw)) {
            return OrionQaReport.compliant(diffSummary);
        }
        String fold = raw.toLowerCase(Locale.ROOT).trim();
        boolean non = fold.startsWith("non_conforme")
                || fold.startsWith("non-conforme")
                || fold.contains("non_conforme")
                || fold.startsWith("non conforme");
        boolean ok = fold.startsWith("conforme") && !non;
        if (!non && ok) {
            return new OrionQaReport(OrionQaReport.Verdict.COMPLIANT,
                    firstLine(raw), Collections.emptyList(), diffSummary, true, true);
        }
        if (!non && !fold.contains("non")) {
            if (fold.contains("conforme") && !fold.contains("non")) {
                return OrionQaReport.compliant(diffSummary);
            }
        }
        if (!non && !ok) {
            return OrionQaReport.compliant(diffSummary);
        }

        List<String> extras = new ArrayList<>();
        String reason = firstLine(raw);
        for (String line : raw.split("\n")) {
            String t = line.trim();
            String lower = t.toLowerCase(Locale.ROOT);
            if (lower.startsWith("ne pas toucher") || lower.contains("contrainte :")
                    || lower.startsWith("- ne pas")) {
                extras.add(t.startsWith("-") ? t.substring(1).trim() : t);
            }
        }
        if (extras.isEmpty()) {
            extras.add("Ne pas toucher : tout ce qui n'est pas dans À faire");
        }
        return OrionQaReport.nonCompliant(reason, extras, diffSummary, false, true);
    }

    public static String augmentMission(String missionBlock, OrionQaReport report) {
        if (report == null || report.isCompliant()) return missionBlock;
        StringBuilder sb = new StringBuilder();
        if (!TextUtils.isEmpty(missionBlock)) sb.append(missionBlock.trim()).append("\n\n");
        sb.append("=== Correction QA (obligatoire) ===\n");
        sb.append("Échec précédent : ").append(report.reason).append('\n');
        sb.append("Ne pas toucher / Contraintes (ajouter) :\n");
        for (String e : report.extraExclusions) {
            sb.append("- ").append(e).append('\n');
        }
        sb.append("Reproduis UNIQUEMENT la demande initiale. "
                + "Interdit de modifier autre chose.\n");
        return sb.toString().trim();
    }

    public static String buildDiffSummary(Map<String, String> before, Map<String, String> after) {
        if (after == null || after.isEmpty()) return "Aucun changement.";
        StringBuilder sb = new StringBuilder();
        int files = 0;
        for (Map.Entry<String, String> e : after.entrySet()) {
            String path = e.getKey();
            String neu = e.getValue() != null ? e.getValue() : "";
            String old = before != null && before.containsKey(path) ? before.get(path) : "";
            if (old == null) old = "";
            if (old.equals(neu)) continue;
            files++;
            String diff = OrionTextDiff.unified(path, old, neu);
            sb.append(diff).append("\n\n");
            if (sb.length() > 8000) {
                sb.append("… (diff tronqué)\n");
                break;
            }
        }
        if (files == 0) return "Aucun changement.";
        return sb.toString().trim();
    }

    static List<String> extractExtraFiles(Map<String, String> before,
            Map<String, String> after, String targetFilename) {
        List<String> extras = new ArrayList<>();
        if (TextUtils.isEmpty(targetFilename) || after == null) return extras;
        String target = basename(targetFilename);
        for (Map.Entry<String, String> e : after.entrySet()) {
            String path = e.getKey();
            String neu = e.getValue() != null ? e.getValue() : "";
            String old = before != null && before.containsKey(path) ? before.get(path) : "";
            if (old == null) old = "";
            if (old.equals(neu)) continue;
            if (!basename(path).equalsIgnoreCase(target)) {
                extras.add(path);
            }
        }
        return extras;
    }

    static boolean diffTouchesOnly(Map<String, String> before, Map<String, String> after,
            String targetFilename) {
        return extractExtraFiles(before, after, targetFilename).isEmpty();
    }

    private static String basename(String path) {
        if (path == null) return "";
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private static int snippetTokens(ResolvedTask t) {
        if (t == null || t.fileLocation == null) return 0;
        return Math.max(1, t.fileLocation.toPromptBlock().length() / 4);
    }

    private static String changedLinesBlob(Map<String, String> before, Map<String, String> after) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : after.entrySet()) {
            String path = e.getKey();
            String neu = e.getValue() != null ? e.getValue() : "";
            String old = before != null && before.containsKey(path) ? before.get(path) : "";
            if (old == null) old = "";
            if (old.equals(neu)) continue;
            String diff = OrionTextDiff.unified(path, old, neu);
            for (String line : diff.split("\n")) {
                if (line.startsWith("+ ") || line.startsWith("- ")) {
                    sb.append(line).append('\n');
                }
            }
        }
        return sb.toString();
    }

    private static List<String> extractKeywords(List<String> exclusions) {
        List<String> out = new ArrayList<>();
        if (exclusions == null) return out;
        for (String e : exclusions) {
            if (e == null) continue;
            String t = e.toLowerCase(Locale.ROOT)
                    .replace("ne pas toucher", " ")
                    .replace("contraintes", " ")
                    .replace(':', ' ')
                    .replace('-', ' ');
            for (String w : t.split("[\\s,;/]+")) {
                w = w.trim();
                if (w.length() < 3) continue;
                if (w.equals("pas") || w.equals("des") || w.equals("les")
                        || w.equals("une") || w.equals("aux")) continue;
                out.add(w);
            }
        }
        return out;
    }

    private static String join(List<String> list) {
        if (list == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String s : list) {
            if (s == null) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(s);
        }
        return sb.toString();
    }

    /** Création Bureau / premier slice — ne pas bloquer multi-fichiers comme une retouche. */
    static boolean looksLikeGreenfieldMission(String missionFold) {
        if (missionFold == null || missionFold.isEmpty()) return false;
        return missionFold.contains("mode greenfield")
                || missionFold.contains("greenfield")
                || missionFold.contains("creation depuis bureau")
                || missionFold.contains("premiere tache")
                || missionFold.contains("un slice minimal")
                || missionFold.contains("projet neuf")
                || missionFold.contains("implemente le plan")
                || missionFold.contains("taches non coche");
    }

    private static String fold(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT)
                .replace('é', 'e').replace('è', 'e').replace('ê', 'e')
                .replace('à', 'a').replace('â', 'a')
                .replace('ô', 'o').replace('ù', 'u')
                .replace('î', 'i').replace('ç', 'c');
    }

    private static String firstLine(String raw) {
        int nl = raw.indexOf('\n');
        return (nl > 0 ? raw.substring(0, nl) : raw).trim();
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "\n…";
    }

    private static String nz(String v, String fallback) {
        return TextUtils.isEmpty(v) ? (fallback != null ? fallback : "") : v.trim();
    }
}
