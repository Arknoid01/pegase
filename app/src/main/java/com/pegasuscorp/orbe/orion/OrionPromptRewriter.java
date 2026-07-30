package com.pegasuscorp.orbe.orion;

import android.content.Context;
import android.text.TextUtils;

import com.pegasuscorp.orbe.orion.prompt.ClarificationManager;
import com.pegasuscorp.orbe.orion.prompt.OrionMode;
import com.pegasuscorp.orbe.orion.prompt.PromptAmbiguityAnalyzer;
import com.pegasuscorp.orbe.orion.prompt.PromptCompiler;
import com.pegasuscorp.orbe.orion.prompt.PromptReadiness;
import com.pegasuscorp.orbe.orion.prompt.ResolvedTask;
import com.pegasuscorp.orbe.orion.prompt.YannGlossary;
import com.pegasuscorp.orbe.orion.search.FileLocation;

/**
 * Façade PromptCompiler — délègue à AmbiguityAnalyzer / ClarificationManager / Compiler.
 */
public final class OrionPromptRewriter {

    public enum CompileKind {
        MISSION,
        NEED_INFO,
        INTERPRETATION
    }

    public static final class CompileResult {
        public final CompileKind kind;
        public final String text;
        public final PromptReadiness readiness;
        public final String learnCandidate;
        public final ResolvedTask task;

        public CompileResult(CompileKind kind, String text) {
            this(kind, text, null, "", null);
        }

        public CompileResult(CompileKind kind, String text, PromptReadiness readiness,
                String learnCandidate) {
            this(kind, text, readiness, learnCandidate, null);
        }

        public CompileResult(CompileKind kind, String text, PromptReadiness readiness,
                String learnCandidate, ResolvedTask task) {
            this.kind = kind;
            this.text = text != null ? text : "";
            this.readiness = readiness != null ? readiness : PromptReadiness.READY;
            this.learnCandidate = learnCandidate != null ? learnCandidate : "";
            this.task = task;
        }

        public boolean needsInfo() {
            return kind == CompileKind.NEED_INFO || kind == CompileKind.INTERPRETATION;
        }
    }

    private OrionPromptRewriter() {}

    public static String userPlaybook(OrionMode mode) {
        OrionMode m = mode != null ? mode : OrionMode.PATCH;
        return YannGlossary.forPrompt(m) + "\n" + YannGlossary.permanentConstraints(m);
    }

    public static String buildMetaPrompt(String raw, String projectHint,
            boolean learnMode, String priorQa, String learnedHint) {
        return buildMetaPrompt(raw, projectHint, learnMode, priorQa, learnedHint, null);
    }

    public static String buildMetaPrompt(String raw, String projectHint,
            boolean learnMode, String priorQa, String learnedHint,
            FileLocation fileLocation) {
        boolean force = !TextUtils.isEmpty(priorQa) || !learnMode;
        OrionMode mode = OrionMode.detect(raw);
        return PromptAmbiguityAnalyzer.buildAnalysisPrompt(
                raw, projectHint, learnedHint, priorQa, force, fileLocation, mode);
    }

    public static String buildMetaPrompt(String raw, String projectHint) {
        return buildMetaPrompt(raw, projectHint, true, null, null, null);
    }

    public static CompileResult parseCompileResult(String raw) {
        return parseCompileResult(null, raw, null);
    }

    public static CompileResult parseCompileResult(Context ctx, String raw, String originalDemand) {
        PromptAmbiguityAnalyzer.Analysis a = PromptAmbiguityAnalyzer.parse(raw);
        switch (a.readiness) {
            case CLARIFICATION_REQUIRED:
                return new CompileResult(CompileKind.NEED_INFO,
                        formatQuestions(a), a.readiness, a.learnCandidate);
            case CLARIFICATION_RECOMMENDED:
                return new CompileResult(CompileKind.INTERPRETATION,
                        a.interpretation, a.readiness, a.learnCandidate);
            case READY:
            default:
                String mission = a.missionBlock;
                if (!TextUtils.isEmpty(mission)) {
                    ResolvedTask task;
                    if (!TextUtils.isEmpty(originalDemand)) {
                        task = PromptCompiler.resolve(ctx, mission, originalDemand);
                    } else {
                        // Pas de demande brute — ne pas detect() sur la mission compilée
                        task = PromptCompiler.resolve(ctx, mission, mission, OrionMode.PATCH);
                    }
                    if (!TextUtils.isEmpty(task.mission) || !TextUtils.isEmpty(task.objective)) {
                        mission = PromptCompiler.compile(task);
                    }
                    return new CompileResult(CompileKind.MISSION, mission,
                            PromptReadiness.READY, a.learnCandidate, task);
                }
                return new CompileResult(CompileKind.MISSION, mission,
                        PromptReadiness.READY, a.learnCandidate, null);
        }
    }

    private static String formatQuestions(PromptAmbiguityAnalyzer.Analysis a) {
        StringBuilder sb = new StringBuilder("QUESTIONS :\n");
        int n = 0;
        for (String q : a.questions) {
            n++;
            sb.append(n).append(". ").append(q).append('\n');
            if (n >= 2) break;
        }
        return sb.toString().trim();
    }

    public static String buildAnswersTemplate(String originalDemand, String besoinBlock) {
        return ClarificationManager.buildQuestionsTemplate(originalDemand,
                questionsFromBlock(besoinBlock));
    }

    public static java.util.List<String> questionsFromBlock(String block) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (block == null) return out;
        for (String line : block.split("\n")) {
            String t = line.trim();
            if (t.startsWith("-")) t = t.substring(1).trim();
            if (t.matches("^\\d+\\.\\s*.*")) {
                t = t.replaceFirst("^\\d+\\.\\s*", "");
            }
            if (t.isEmpty() || t.toUpperCase(java.util.Locale.ROOT).startsWith("QUESTIONS")) {
                continue;
            }
            out.add(t);
            if (out.size() >= 2) break;
        }
        return out;
    }

    public static String buildInterpretationUi(String demand, String interpretation) {
        return ClarificationManager.buildInterpretationTemplate(demand, interpretation);
    }

    public static String extractPriorQa(String fieldText) {
        ClarificationManager cm = new ClarificationManager();
        return cm.extractUserReply(fieldText);
    }

    public static String extractOriginalDemand(String fieldText, String fallback) {
        return ClarificationManager.extractDemand(fieldText, fallback);
    }

    public static String cleanRewritten(String raw) {
        if (raw == null) return "";
        String t = raw.trim();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            if (nl > 0) t = t.substring(nl + 1);
            int end = t.lastIndexOf("```");
            if (end > 0) t = t.substring(0, end);
            t = t.trim();
        }
        return t.trim();
    }

    public static String projectHint(Context ctx) {
        if (ctx == null) return "";
        try {
            OrionProjectStore store = OrionProjectStore.get(ctx);
            String active = store.getActiveProject();
            if (TextUtils.isEmpty(active)) return "";
            StringBuilder sb = new StringBuilder();
            sb.append("Projet actif : ").append(active).append('\n');
            java.util.List<OrionProjectStore.ProjectFile> files = store.getProjectFiles();
            if (files == null || files.isEmpty()) {
                sb.append("(dossier vide)\n");
                return sb.toString();
            }
            sb.append("Fichiers : ");
            int n = 0;
            for (OrionProjectStore.ProjectFile pf : files) {
                if (pf == null || TextUtils.isEmpty(pf.name)) continue;
                if (n > 0) sb.append(", ");
                sb.append(pf.name);
                n++;
                if (n >= 12) {
                    sb.append("…");
                    break;
                }
            }
            sb.append('\n');
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
