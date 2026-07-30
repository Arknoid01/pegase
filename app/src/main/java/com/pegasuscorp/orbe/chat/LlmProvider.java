package com.pegasuscorp.orbe.chat;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Un fournisseur LLM OpenAI-compatible dans la chaîne multi-provider. */
public final class LlmProvider {

    public static final String ID_GROQ = "groq";
    public static final String ID_CEREBRAS = "cerebras";
    public static final String ID_OPENROUTER = "openrouter";

    public final String id;
    public final String displayName;
    public final String modelId;
    public final String apiKey;
    public final String chatCompletionsUrl;
    public final boolean nativeFc;
    public final int priority;
    public final int connectTimeoutMs;
    public final int readTimeoutMs;
    public final Map<String, String> extraHeaders;

    public LlmProvider(String id, String displayName, String modelId, String apiKey,
            String chatCompletionsUrl, boolean nativeFc, int priority,
            int connectTimeoutMs, int readTimeoutMs, Map<String, String> extraHeaders) {
        this.id = id;
        this.displayName = displayName;
        this.modelId = modelId;
        this.apiKey = apiKey != null ? apiKey : "";
        this.chatCompletionsUrl = chatCompletionsUrl;
        this.nativeFc = nativeFc;
        this.priority = priority;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.extraHeaders = extraHeaders != null
                ? Collections.unmodifiableMap(new LinkedHashMap<>(extraHeaders))
                : Collections.emptyMap();
    }

    public boolean hasKey() {
        return apiKey != null && !apiKey.trim().isEmpty();
    }

    /** Timeouts asymétriques : Groq un peu plus patient, les autres agressifs. */
    public static int readTimeoutMsFor(String providerId, boolean agenticOrBureau) {
        if (agenticOrBureau) return 20_000;
        if (ID_GROQ.equals(providerId)) return 15_000;
        return 10_000;
    }

    public static int connectTimeoutMs() {
        return 5_000;
    }
}
