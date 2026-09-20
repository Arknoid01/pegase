package com.pegasuscorp.orbe.chat;

import com.pegasuscorp.orbe.session.AgenticTurnPolicy;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Chaîne assistant/tool pour la boucle agentique multi-hop. */
public final class AgenticChain {

    public static final class Step {
        public final LlmReply assistantReply;
        public final NativeToolCall toolCall;
        /** Contenu outil pour le LLM (peut être des extraits web bruts). */
        public final String toolResultContent;
        /** Texte affichable si la synthèse LLM échoue. */
        public final String toolDisplayText;
        /**
         * Appel refusé avant exécution (arguments invalides, sortie tronquée) : le modèle voit le
         * motif et peut corriger, mais l'étape ne compte ni dans le plafond ni dans l'anti-doublon.
         */
        public final boolean rejected;

        Step(LlmReply assistantReply, NativeToolCall toolCall, String toolResultContent,
                String toolDisplayText, boolean rejected) {
            this.rejected = rejected;
            this.assistantReply = assistantReply;
            this.toolCall = toolCall;
            this.toolResultContent = toolResultContent != null ? toolResultContent : "";
            this.toolDisplayText = toolDisplayText != null && !toolDisplayText.isEmpty()
                    ? toolDisplayText
                    : this.toolResultContent;
        }
    }

    public final List<ChatBackend.Turn> history;
    public final String userMessage;
    private final List<Step> steps = new ArrayList<>();
    /** Consignes ajoutées par l'utilisateur pendant le tour (steering), après les étapes. */
    private final List<String> steering = new ArrayList<>();

    public AgenticChain(List<ChatBackend.Turn> history, String userMessage) {
        this.history = history != null ? new ArrayList<>(history) : new ArrayList<>();
        this.userMessage = userMessage != null ? userMessage : "";
    }

    /** Compat mono-étape. */
    public AgenticChain(AgenticContinuation cont) {
        this(cont.history, cont.userMessage);
        if (cont.toolCall != null) {
            addStep(cont.assistantToolReply, cont.toolCall, cont.toolResultContent);
        }
    }

    public void addStep(LlmReply assistantReply, NativeToolCall toolCall, String toolResultContent) {
        addStep(assistantReply, toolCall, toolResultContent, toolResultContent);
    }

    public void addStep(LlmReply assistantReply, NativeToolCall toolCall,
            String toolResultContent, String toolDisplayText) {
        if (toolCall == null) return;
        steps.add(new Step(assistantReply, toolCall, toolResultContent, toolDisplayText, false));
    }

    /** Enregistre un appel refusé avant exécution, avec le motif renvoyé au modèle. */
    public void addRejectedStep(LlmReply assistantReply, NativeToolCall toolCall,
            String errorJson) {
        if (toolCall == null) return;
        steps.add(new Step(assistantReply, toolCall, errorJson, "", true));
    }

    /** Nombre d'appels refusés dans ce tour (bornés par la session pour éviter les boucles). */
    public int rejectedStepCount() {
        int n = 0;
        for (Step step : steps) if (step.rejected) n++;
        return n;
    }

    public void addSteering(String text) {
        if (text != null && !text.trim().isEmpty()) steering.add(text.trim());
    }

    public List<String> steering() {
        return Collections.unmodifiableList(steering);
    }

    public List<Step> steps() {
        return Collections.unmodifiableList(steps);
    }

    public boolean isEmpty() {
        return steps.isEmpty();
    }

    public String lastToolResult() {
        Step last = lastExecutedStep();
        return last != null ? last.toolResultContent : "";
    }

    public String lastToolDisplayText() {
        Step last = lastExecutedStep();
        return last != null ? last.toolDisplayText : "";
    }

    private Step lastExecutedStep() {
        for (int i = steps.size() - 1; i >= 0; i--) {
            if (!steps.get(i).rejected) return steps.get(i);
        }
        return null;
    }

    /** Outils réellement exécutés (les appels refusés ne comptent pas). */
    public int toolStepCount() {
        return steps.size() - rejectedStepCount();
    }

    public boolean usedSearch() {
        return usedTool("search");
    }

    /** Nombre d'exécutions (hors refusées) d'un outil dans la chaîne. */
    public int toolUseCount(String toolName) {
        if (toolName == null || toolName.trim().isEmpty()) return 0;
        String want = toolName.trim().toLowerCase(java.util.Locale.ROOT);
        int n = 0;
        for (Step step : steps) {
            if (step.rejected) continue;
            if (want.equals(step.toolCall.name.trim().toLowerCase(java.util.Locale.ROOT))) n++;
        }
        return n;
    }

    /** Un outil sensible a servi dans ce tour : la réponse finale ne doit pas être persistée. */
    public boolean usedSensitiveTool() {
        for (Step step : steps) {
            if (com.pegasuscorp.orbe.tools.SensitiveTools.isSensitive(step.toolCall.name)) {
                return true;
            }
        }
        return false;
    }

    public boolean usedTool(String toolName) {
        if (toolName == null || toolName.trim().isEmpty()) return false;
        String want = toolName.trim().toLowerCase(java.util.Locale.ROOT);
        for (Step step : steps) {
            if (step.rejected) continue;
            if (want.equals(step.toolCall.name.trim().toLowerCase(java.util.Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    public boolean hasToolCall(String toolName, JSONObject args) {
        String fp = AgenticTurnPolicy.fingerprint(toolName, args);
        for (Step step : steps) {
            if (step.rejected) continue;
            if (AgenticTurnPolicy.fingerprint(step.toolCall.name, step.toolCall.arguments).equals(fp)) {
                return true;
            }
        }
        return false;
    }
}
