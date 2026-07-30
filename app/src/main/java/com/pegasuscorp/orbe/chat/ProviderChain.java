package com.pegasuscorp.orbe.chat;

import android.content.Context;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Chaîne cloud : Groq → Cerebras → OpenRouter.
 * Gemini volontairement exclu (souvent indisponible).
 */
public final class ProviderChain {

    /** Même famille GPT-OSS 120B — IDs spécifiques par API. */
    public static final String CEREBRAS_MODEL = "gpt-oss-120b";
    public static final String OPENROUTER_MODEL = "openai/gpt-oss-120b";

    public static final String GROQ_URL = "https://api.groq.com/openai/v1/chat/completions";
    public static final String CEREBRAS_URL = "https://api.cerebras.ai/v1/chat/completions";
    public static final String OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions";

    private ProviderChain() {}

    /**
     * Providers configurés (clé présente), ordonnés.
     * @param agenticOrBureau timeouts lecture allongés (20s)
     */
    public static List<LlmProvider> build(Context ctx, boolean agenticOrBureau) {
        List<LlmProvider> all = buildAll(ctx, agenticOrBureau);
        List<LlmProvider> out = new ArrayList<>();
        for (LlmProvider p : all) {
            if (p.hasKey()) out.add(p);
        }
        return out;
    }

    /** Tous les providers (même sans clé) — pour UI / tests. */
    public static List<LlmProvider> buildAll(Context ctx, boolean agenticOrBureau) {
        List<LlmProvider> out = new ArrayList<>();
        if (ctx == null) return out;

        String groqModel = CloudModelStore.getGroqModelId(ctx);
        out.add(new LlmProvider(
                LlmProvider.ID_GROQ,
                "Groq",
                groqModel,
                ApiKeyStore.getGroqKey(ctx),
                GROQ_URL,
                true,
                1,
                LlmProvider.connectTimeoutMs(),
                LlmProvider.readTimeoutMsFor(LlmProvider.ID_GROQ, agenticOrBureau),
                null));

        out.add(new LlmProvider(
                LlmProvider.ID_CEREBRAS,
                "Cerebras",
                CEREBRAS_MODEL,
                ApiKeyStore.getCerebrasKey(ctx),
                CEREBRAS_URL,
                true,
                2,
                LlmProvider.connectTimeoutMs(),
                LlmProvider.readTimeoutMsFor(LlmProvider.ID_CEREBRAS, agenticOrBureau),
                null));

        Map<String, String> orHeaders = new LinkedHashMap<>();
        orHeaders.put("HTTP-Referer", "https://pegase.local");
        orHeaders.put("X-Title", "Pegase");
        out.add(new LlmProvider(
                LlmProvider.ID_OPENROUTER,
                "OpenRouter",
                OPENROUTER_MODEL,
                ApiKeyStore.getOpenRouterKey(ctx),
                OPENROUTER_URL,
                true,
                3,
                LlmProvider.connectTimeoutMs(),
                LlmProvider.readTimeoutMsFor(LlmProvider.ID_OPENROUTER, agenticOrBureau),
                orHeaders));

        return out;
    }

    /**
     * Modèles à essayer pour un provider avant de passer au suivant.
     * Groq : modèle préféré (+ 120B si autre) — puis Cerebras / OpenRouter (même 120B).
     */
    public static String[][] modelsFor(LlmProvider provider) {
        if (provider == null) return new String[0][2];
        if (LlmProvider.ID_GROQ.equals(provider.id)) {
            return CloudModelStore.groqFallbackChain(provider.modelId);
        }
        String label = provider.displayName + " · " + provider.modelId;
        return new String[][]{{provider.modelId, label}};
    }
}
