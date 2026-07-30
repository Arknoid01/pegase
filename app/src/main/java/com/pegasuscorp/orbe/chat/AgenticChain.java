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

        Step(LlmReply assistantReply, NativeToolCall toolCall, String toolResultContent,
                String toolDisplayText) {
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
        steps.add(new Step(assistantReply, toolCall, toolResultContent, toolDisplayText));
    }

    public List<Step> steps() {
        return Collections.unmodifiableList(steps);
    }

    public boolean isEmpty() {
        return steps.isEmpty();
    }

    public String lastToolResult() {
        if (steps.isEmpty()) return "";
        return steps.get(steps.size() - 1).toolResultContent;
    }

    public String lastToolDisplayText() {
        if (steps.isEmpty()) return "";
        return steps.get(steps.size() - 1).toolDisplayText;
    }

    public int toolStepCount() {
        return steps.size();
    }

    public boolean usedSearch() {
        return usedTool("search");
    }

    public boolean usedTool(String toolName) {
        if (toolName == null || toolName.trim().isEmpty()) return false;
        String want = toolName.trim().toLowerCase(java.util.Locale.ROOT);
        for (Step step : steps) {
            if (want.equals(step.toolCall.name.trim().toLowerCase(java.util.Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    public boolean hasToolCall(String toolName, JSONObject args) {
        String fp = AgenticTurnPolicy.fingerprint(toolName, args);
        for (Step step : steps) {
            if (AgenticTurnPolicy.fingerprint(step.toolCall.name, step.toolCall.arguments).equals(fp)) {
                return true;
            }
        }
        return false;
    }
}
