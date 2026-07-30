package com.pegasuscorp.orbe.orion.prompt;

import android.text.TextUtils;

import com.pegasuscorp.orbe.orion.search.FileLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Détecte ce qui manque et produit le méta-prompt LLM + parse la réponse structurée.
 */
public final class PromptAmbiguityAnalyzer {

    public static final class Analysis {
        public final PromptReadiness readiness;
        public final String interpretation;
        public final List<String> questions;
        public final String missionBlock;
        public final String learnCandidate; // préférence proposée, optionnel

        public Analysis(PromptReadiness readiness, String interpretation,
                List<String> questions, String missionBlock, String learnCandidate) {
            this.readiness = readiness != null ? readiness : PromptReadiness.READY;
            this.interpretation = interpretation != null ? interpretation : "";
            this.questions = questions != null ? questions : new ArrayList<>();
            this.missionBlock = missionBlock != null ? missionBlock : "";
            this.learnCandidate = learnCandidate != null ? learnCandidate : "";
        }
    }

    private PromptAmbiguityAnalyzer() {}

    public static String buildAnalysisPrompt(String demand, String projectHint,
            String learnedPrefs, String userReply, boolean forceCompile) {
        return buildAnalysisPrompt(demand, projectHint, learnedPrefs, userReply,
                forceCompile, null, OrionMode.detect(demand));
    }

    public static String buildAnalysisPrompt(String demand, String projectHint,
            String learnedPrefs, String userReply, boolean forceCompile,
            FileLocation fileLocation) {
        return buildAnalysisPrompt(demand, projectHint, learnedPrefs, userReply,
                forceCompile, fileLocation, OrionMode.detect(demand));
    }

    public static String buildAnalysisPrompt(String demand, String projectHint,
            String learnedPrefs, String userReply, boolean forceCompile,
            FileLocation fileLocation, OrionMode mode) {
        OrionMode m = mode != null ? mode : OrionMode.PATCH;
        StringBuilder sb = new StringBuilder();
        sb.append("Tu es PromptAmbiguityAnalyzer + PromptCompiler de Pégase ")
                .append("(GPT-OSS 120B — bon en interprétation).\n")
                .append("Tu prépares des missions pour Orion (codeur). ").append('\n')
                .append("Tu ne poses PAS de questions systématiquement.\n\n")
                .append("Règles anti-interrogatoire :\n")
                .append("- Max 2 questions si REQUIRED.\n")
                .append("- Une question seulement si elle change vraiment le patch technique.\n")
                .append("- Préférer INTERPRETATION proposée (RECOMMENDED) plutôt que quiz.\n")
                .append("- Toujours permettre « fais au mieux ».\n")
                .append("- Ne pas redemander une info déjà dans le projet / préférences.\n")
                .append("- « Plus de particules » flou → RECOMMENDED avec interprétation orbe/densité.\n")
                .append("- « Supprime le titre Bureau sans toucher à la barre » → READY.\n\n")
                .append(YannGlossary.forPrompt(m)).append('\n')
                .append(YannGlossary.permanentConstraints(m)).append('\n');

        if (!TextUtils.isEmpty(learnedPrefs)) {
            sb.append("=== Préférences confirmées de Yann ===\n")
                    .append(learnedPrefs.trim()).append("\n\n");
        }
        if (!TextUtils.isEmpty(projectHint)) {
            sb.append("=== Projet Orion ===\n").append(projectHint.trim()).append("\n\n");
        }
        if (fileLocation != null) {
            sb.append("=== Code concerné ===\n")
                    .append(fileLocation.toPromptBlock()).append("\n")
                    .append("Planifie en tenant compte de ce code exact.\n")
                    .append("Identifie précisément ce qui doit changer.\n\n");
        }
        sb.append("=== Demande ===\n").append(demand == null ? "" : demand.trim()).append("\n\n");

        if (forceCompile || !TextUtils.isEmpty(userReply)) {
            sb.append("=== Réponse / validation de Yann ===\n")
                    .append(TextUtils.isEmpty(userReply) ? "(fais au mieux)" : userReply.trim())
                    .append("\n\n")
                    .append("Force READINESS: READY. Produis le bloc Mission complet.\n")
                    .append("Documente les hypothèses si « oui » ou « fais au mieux ».\n")
                    .append("Pas de QUESTIONS. Pas de INTERPRETATION seule.\n\n");
        }

        sb.append("Sortie OBLIGATOIRE (sans fences, sans intro) — un des formats :\n\n")
                .append("A) READINESS: READY\n")
                .append("Mission : …\n")
                .append("Contexte :\n- …\n")
                .append("Mot-clé ciblé :\n")
                .append("<1-3 mots ou null si trop large>\n")
                .append("Objectif principal :\n…\n")
                .append("À faire :\n- …\n")
                .append("Ne pas toucher / Contraintes :\n- …\n")
                .append("Validation :\n- …\n")
                .append("(Hypothèses retenues / À investiguer si utile)\n\n")
                .append("B) READINESS: RECOMMENDED\n")
                .append("INTERPRETATION:\n")
                .append("Je comprends : … C'est bien ça ?\n")
                .append("(Options implicites : oui / corrige : … / fais au mieux)\n\n")
                .append("C) READINESS: REQUIRED\n")
                .append("QUESTIONS:\n")
                .append("- Q1 (avec options A/B si possible) ?\n")
                .append("- Q2 optionnelle ?\n\n")
                .append("Règle keyword : si la mission cible un élément précis du code, ")
                .append("extrais le mot-clé technique principal (1-3 mots). ")
                .append("Exemples : « plus de particules » → particleCount ; ")
                .append("« changer la couleur du fond » → background ; ")
                .append("« bouton de relance » → restartButton ; ")
                .append("« refais toute l'UI » → null.\n")
                .append("Missions larges sans keyword → Orion les classera LARGE/MASSIVE.\n\n")
                .append("Optionnel en fin de B ou C :\n")
                .append("LEARN_CANDIDATE: quand Yann dit « … », il veut généralement …\n");
        return sb.toString();
    }

    public static Analysis parse(String raw) {
        String t = stripFences(raw);
        if (TextUtils.isEmpty(t)) {
            return new Analysis(PromptReadiness.READY, "", new ArrayList<>(), "", "");
        }
        PromptReadiness readiness = detectReadiness(t);
        String learn = extractLearnCandidate(t);
        String interpretation = extractBlock(t, "INTERPRETATION");
        List<String> questions = extractQuestions(t);
        String mission = "";
        if (t.toLowerCase(Locale.ROOT).contains("mission :")
                || t.toLowerCase(Locale.ROOT).contains("mission:")) {
            int i = indexIgnore(t, "Mission");
            if (i >= 0) mission = t.substring(i).trim();
            // Couper LEARN_CANDIDATE
            int lc = indexIgnore(mission, "LEARN_CANDIDATE");
            if (lc > 0) mission = mission.substring(0, lc).trim();
        }

        if (readiness == PromptReadiness.READY && TextUtils.isEmpty(mission)
                && !TextUtils.isEmpty(interpretation) && questions.isEmpty()) {
            // Interprétation seule → RECOMMENDED
            readiness = PromptReadiness.CLARIFICATION_RECOMMENDED;
        }
        if (readiness == PromptReadiness.CLARIFICATION_REQUIRED && questions.isEmpty()
                && !TextUtils.isEmpty(interpretation)) {
            readiness = PromptReadiness.CLARIFICATION_RECOMMENDED;
        }
        if (readiness == PromptReadiness.READY && TextUtils.isEmpty(mission)) {
            // Fallback : tout le texte comme mission
            mission = t;
        }
        // Cap questions
        if (questions.size() > 2) {
            questions = new ArrayList<>(questions.subList(0, 2));
        }
        return new Analysis(readiness, interpretation, questions, mission, learn);
    }

    private static PromptReadiness detectReadiness(String t) {
        String lower = t.toLowerCase(Locale.ROOT);
        if (lower.contains("readiness: required") || lower.contains("readiness : required")
                || lower.startsWith("besoin_infos") || lower.contains("\nquestions:")) {
            if (lower.contains("readiness: ready") && lower.contains("mission")) {
                return PromptReadiness.READY;
            }
            if (lower.contains("questions:")) return PromptReadiness.CLARIFICATION_REQUIRED;
        }
        if (lower.contains("readiness: recommended")
                || lower.contains("readiness : recommended")
                || lower.contains("interprétation:")
                || lower.contains("interpretation:")) {
            return PromptReadiness.CLARIFICATION_RECOMMENDED;
        }
        if (lower.contains("readiness: ready") || lower.contains("mission :")) {
            return PromptReadiness.READY;
        }
        if (lower.contains("questions:")) return PromptReadiness.CLARIFICATION_REQUIRED;
        return PromptReadiness.READY;
    }

    private static String extractBlock(String t, String header) {
        int i = indexIgnore(t, header);
        if (i < 0) return "";
        String rest = t.substring(i);
        int colon = rest.indexOf(':');
        if (colon >= 0) rest = rest.substring(colon + 1);
        // jusqu'à prochaine section
        String[] stops = {"QUESTIONS", "READINESS", "Mission", "LEARN_CANDIDATE",
                "Contexte", "Objectif"};
        int end = rest.length();
        for (String s : stops) {
            int j = indexIgnore(rest, s);
            if (j > 10 && j < end) end = j;
        }
        return rest.substring(0, end).trim();
    }

    private static List<String> extractQuestions(String t) {
        List<String> out = new ArrayList<>();
        int i = indexIgnore(t, "QUESTIONS");
        if (i < 0 && indexIgnore(t, "BESOIN_INFOS") >= 0) {
            i = indexIgnore(t, "BESOIN_INFOS");
        }
        if (i < 0) return out;
        String rest = t.substring(i);
        int colon = rest.indexOf(':');
        if (colon >= 0) rest = rest.substring(colon + 1);
        int lc = indexIgnore(rest, "LEARN_CANDIDATE");
        if (lc > 0) rest = rest.substring(0, lc);
        for (String line : rest.split("\n")) {
            String s = line.trim();
            if (s.startsWith("-")) s = s.substring(1).trim();
            if (s.isEmpty()) continue;
            if (s.toLowerCase(Locale.ROOT).startsWith("readiness")) break;
            if (s.toLowerCase(Locale.ROOT).startsWith("mission")) break;
            out.add(s);
            if (out.size() >= 2) break;
        }
        return out;
    }

    private static String extractLearnCandidate(String t) {
        int i = indexIgnore(t, "LEARN_CANDIDATE");
        if (i < 0) return "";
        String rest = t.substring(i);
        int colon = rest.indexOf(':');
        if (colon >= 0) rest = rest.substring(colon + 1).trim();
        int nl = rest.indexOf('\n');
        if (nl > 0) rest = rest.substring(0, nl).trim();
        return rest;
    }

    private static String stripFences(String raw) {
        if (raw == null) return "";
        String t = raw.trim();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            if (nl > 0) t = t.substring(nl + 1);
            int end = t.lastIndexOf("```");
            if (end > 0) t = t.substring(0, end);
        }
        return t.trim();
    }

    private static int indexIgnore(String hay, String needle) {
        return hay.toLowerCase(Locale.ROOT).indexOf(needle.toLowerCase(Locale.ROOT));
    }
}
