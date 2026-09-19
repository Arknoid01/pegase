package com.pegasuscorp.orbe.chat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Réponse LLM — texte libre et/ou appels d'outils natifs. */
public final class LlmReply {

    public final String content;
    public final List<NativeToolCall> toolCalls;
    public final boolean nativeToolCalls;
    /** {@code finish_reason} du fournisseur ("stop", "tool_calls", "length"…), "" si inconnu. */
    public final String finishReason;

    private LlmReply(String content, List<NativeToolCall> toolCalls, boolean nativeToolCalls,
            String finishReason) {
        this.content = content;
        this.toolCalls = toolCalls != null ? toolCalls : Collections.emptyList();
        this.nativeToolCalls = nativeToolCalls;
        this.finishReason = finishReason != null ? finishReason : "";
    }

    public static LlmReply text(String content) {
        return new LlmReply(content, Collections.emptyList(), false, "");
    }

    public static LlmReply text(String content, String finishReason) {
        return new LlmReply(content, Collections.emptyList(), false, finishReason);
    }

    public static LlmReply withNativeToolCalls(String content, List<NativeToolCall> toolCalls) {
        return new LlmReply(content, toolCalls, true, "");
    }

    public static LlmReply withNativeToolCalls(String content, List<NativeToolCall> toolCalls,
            String finishReason) {
        return new LlmReply(content, toolCalls, true, finishReason);
    }

    public boolean hasNativeToolCalls() {
        return nativeToolCalls && !toolCalls.isEmpty();
    }
}
