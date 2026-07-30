package com.pegasuscorp.orbe.chat;

import java.util.ArrayList;
import java.util.List;

/** Contexte pour le 2ᵉ appel LLM après exécution d'un outil (boucle agentique). */
public final class AgenticContinuation {

    public final List<ChatBackend.Turn> history;
    public final String userMessage;
    public final LlmReply assistantToolReply;
    public final NativeToolCall toolCall;
    public final String toolResultContent;

    public AgenticContinuation(List<ChatBackend.Turn> history, String userMessage,
            LlmReply assistantToolReply, NativeToolCall toolCall, String toolResultContent) {
        this.history = history != null ? new ArrayList<>(history) : new ArrayList<>();
        this.userMessage = userMessage != null ? userMessage : "";
        this.assistantToolReply = assistantToolReply;
        this.toolCall = toolCall;
        this.toolResultContent = toolResultContent != null ? toolResultContent : "";
    }
}
