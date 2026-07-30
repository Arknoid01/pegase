package com.pegasuscorp.orbe.orion.prompt;

import android.text.TextUtils;

import java.util.List;
import java.util.Locale;

/**
 * Conserve le tour de clarification (max 1) et produit les gabarits UI.
 */
public final class ClarificationManager {

    public enum Phase {
        IDLE,
        AWAITING_VALIDATION,
        AWAITING_ANSWERS,
        DONE
    }

    private Phase phase = Phase.IDLE;
    private String originalDemand = "";
    private String pendingInterpretation = "";
    private String pendingLearnCandidate = "";
    private int clarificationTurns;

    public Phase getPhase() {
        return phase;
    }

    public String getOriginalDemand() {
        return originalDemand;
    }

    public String getPendingLearnCandidate() {
        return pendingLearnCandidate;
    }

    public void clear() {
        phase = Phase.IDLE;
        originalDemand = "";
        pendingInterpretation = "";
        pendingLearnCandidate = "";
        clarificationTurns = 0;
    }

    public void begin(String demand) {
        clear();
        originalDemand = demand == null ? "" : demand.trim();
    }

    public boolean canClarify() {
        return clarificationTurns < 1;
    }

    /** Gabarit conversationnel selon readiness. */
    public String buildUiPrompt(PromptAmbiguityAnalyzer.Analysis analysis) {
        if (analysis == null) return "";
        pendingLearnCandidate = analysis.learnCandidate;
        switch (analysis.readiness) {
            case CLARIFICATION_RECOMMENDED:
                phase = Phase.AWAITING_VALIDATION;
                clarificationTurns = 1;
                pendingInterpretation = analysis.interpretation;
                return buildInterpretationTemplate(originalDemand, analysis.interpretation);
            case CLARIFICATION_REQUIRED:
                phase = Phase.AWAITING_ANSWERS;
                clarificationTurns = 1;
                return buildQuestionsTemplate(originalDemand, analysis.questions);
            case READY:
            default:
                phase = Phase.DONE;
                return analysis.missionBlock;
        }
    }

    public static String buildInterpretationTemplate(String demand, String interpretation) {
        StringBuilder sb = new StringBuilder();
        sb.append("Ma demande : ").append(nz(demand)).append("\n\n");
        sb.append(nz(interpretation)).append("\n\n");
        sb.append("Ta réponse (oui / corrige : … / fais au mieux) :\n");
        sb.append("oui");
        return sb.toString();
    }

    public static String buildQuestionsTemplate(String demand, List<String> questions) {
        StringBuilder sb = new StringBuilder();
        sb.append("Ma demande : ").append(nz(demand)).append("\n\n");
        sb.append("QUESTIONS :\n");
        int n = 0;
        if (questions != null) {
            for (String q : questions) {
                if (TextUtils.isEmpty(q)) continue;
                n++;
                sb.append(n).append(". ").append(q.trim()).append('\n');
                if (n >= 2) break;
            }
        }
        sb.append("\nMes réponses (ou « fais au mieux ») :\n");
        sb.append("1. \n");
        if (n >= 2) sb.append("2. \n");
        return sb.toString();
    }

    /** Extrait la réponse utilisateur du champ pour la 2ᵉ passe. */
    public String extractUserReply(String fieldText) {
        if (TextUtils.isEmpty(fieldText)) return "fais au mieux";
        String t = fieldText.trim();
        String lower = t.toLowerCase(Locale.ROOT);
        if (lower.contains("ta réponse")) {
            int i = lower.lastIndexOf("ta réponse");
            String rest = t.substring(i);
            int colon = rest.indexOf(':');
            if (colon >= 0) rest = rest.substring(colon + 1).trim();
            // enlever sous-hint
            if (rest.toLowerCase(Locale.ROOT).startsWith("(oui")) {
                int nl = rest.indexOf('\n');
                if (nl >= 0) rest = rest.substring(nl + 1).trim();
            }
            return rest.isEmpty() ? "oui" : rest;
        }
        if (lower.contains("mes réponses")) {
            int i = lower.indexOf("mes réponses");
            return t.substring(i).trim();
        }
        if (lower.startsWith("oui") || lower.startsWith("non")
                || lower.contains("fais au mieux") || lower.startsWith("corrige")) {
            return t;
        }
        return t;
    }

    public static String extractDemand(String field, String fallback) {
        if (TextUtils.isEmpty(field)) return nz(fallback);
        String t = field.trim();
        if (t.toLowerCase(Locale.ROOT).startsWith("ma demande :")) {
            int nl = t.indexOf('\n');
            String line = nl > 0
                    ? t.substring("Ma demande :".length(), nl).trim()
                    : t.substring("Ma demande :".length()).trim();
            if (!line.isEmpty()) return line;
        }
        return nz(fallback);
    }

    public boolean looksLikeClarificationField(String field) {
        if (TextUtils.isEmpty(field)) return false;
        String lower = field.toLowerCase(Locale.ROOT);
        return lower.contains("ta réponse") || lower.contains("mes réponses")
                || lower.contains("questions :") || lower.startsWith("ma demande :");
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }
}
