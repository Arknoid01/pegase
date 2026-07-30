package com.pegasuscorp.orbe.chat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Réponse LLM — texte libre et/ou appels d'outils natifs. */
public final class LlmReply {

    public final String content;
    public final List<NativeToolCall> toolCalls;
    public final boolean nativeToolCalls;

    private LlmReply(String content, List<NativeToolCall> toolCalls, boolean nativeToolCalls) {
        this.content = content;
        this.toolCalls = toolCalls != null ? toolCalls : Collections.emptyList();
        this.nativeToolCalls = nativeToolCalls;
    }

    public static LlmReply text(String content) {
        return new LlmReply(content, Collections.emptyList(), false);
    }

    public static LlmReply withNativeToolCalls(String content, List<NativeToolCall> toolCalls) {
        return new LlmReply(content, toolCalls, true);
    }

    public boolean hasNativeToolCalls() {
        return nativeToolCalls && !toolCalls.isEmpty();
    }
}
