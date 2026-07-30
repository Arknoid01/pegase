package com.pegasuscorp.orbe.chat;

import android.content.Context;

import com.pegasuscorp.orbe.llm.ModelStore;

/**
 * Fournisseur cloud et modèle actif (Groq ou Gemini).
 */
public final class CloudModelStore {

    public static final String PROVIDER_GROQ = "groq";
    public static final String PROVIDER_GEMINI = "gemini";

    // Groq — IDs console (juillet 2026)
    public static final String GROQ_GPT_OSS_120B = "openai/gpt-oss-120b";
    public static final String GROQ_GPT_OSS_20B = "openai/gpt-oss-20b";
    /** @deprecated Retiré de Groq (404) — ne plus utiliser en rotation. */
    @Deprecated
    public static final String GROQ_LLAMA_4_SCOUT =
            "meta-llama/llama-4-scout-17b-16e-instruct";
    public static final String GROQ_QWEN_3_32B = "qwen/qwen3-32b";
    public static final String GROQ_QWEN_36_27B = "qwen/qwen3.6-27b";

    /**
     * Catalogue UI Groq. Défaut = 120B.
     * Fallback RPM entre opérateurs = {@link ProviderChain} (Groq → Cerebras → OpenRouter),
     * toujours GPT-OSS 120B — pas de 2ᵉ modèle Groq.
     */
    public static final String[][] GROQ_MODELS = {
            {GROQ_GPT_OSS_120B, "GPT-OSS 120B"},
            {GROQ_GPT_OSS_20B, "GPT-OSS 20B"},
            {GROQ_QWEN_3_32B, "Qwen 3 32B"},
            {GROQ_QWEN_36_27B, "Qwen 3.6 27B"},
    };

    /**
     * Modèles Groq à réessayer avant de passer à Cerebras.
     * Un seul : le 120B (la rotation multi-opérateur est dans {@link ProviderChain}).
     */
    public static final String[][] GROQ_ROTATION = {
            {GROQ_GPT_OSS_120B, "GPT-OSS 120B"},
    };

    // Gemini
    public static final String GEMINI_FLASH = "gemini-2.0-flash";
    public static final String GEMINI_FLASH_LITE = "gemini-2.0-flash-lite";
    public static final String GEMINI_25_FLASH = "gemini-2.5-flash-preview-05-20";
    public static final String GEMINI_15_PRO = "gemini-1.5-pro";

    public static final String[][] GEMINI_MODELS = {
            {GEMINI_FLASH, "Gemini 2.0 Flash"},
            {GEMINI_FLASH_LITE, "Gemini 2.0 Flash Lite"},
            {GEMINI_25_FLASH, "Gemini 2.5 Flash"},
            {GEMINI_15_PRO, "Gemini 1.5 Pro"},
    };

    private static final String PREFS = "orbe_cloud_llm";
    private static final String KEY_PROVIDER = "cloud_provider";
    private static final String KEY_GROQ_MODEL = "groq_model_id";
    private static final String KEY_GEMINI_MODEL = "gemini_model_id";

    private CloudModelStore() {}

    public static String getActiveProvider(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_PROVIDER, PROVIDER_GROQ);
    }

    public static void setActiveProvider(Context context, String provider) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_PROVIDER, provider)
                .apply();
    }

    public static String getGroqModelId(Context context) {
        String id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_GROQ_MODEL, GROQ_GPT_OSS_120B);
        if (id == null || id.isEmpty() || isRetiredGroqModel(id)) {
            return GROQ_GPT_OSS_120B;
        }
        return id;
    }

    /** Scout et autres IDs Groq morts / retirés du catalogue. */
    public static boolean isRetiredGroqModel(String modelId) {
        return GROQ_LLAMA_4_SCOUT.equals(modelId);
    }

    public static void setGroqModelId(Context context, String modelId) {
        String id = modelId;
        if (id == null || id.isEmpty() || isRetiredGroqModel(id)) {
            id = GROQ_GPT_OSS_120B;
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_GROQ_MODEL, id)
                .apply();
    }

    public static String getGeminiModelId(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_GEMINI_MODEL, GEMINI_FLASH);
    }

    public static void setGeminiModelId(Context context, String modelId) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_GEMINI_MODEL, modelId)
                .apply();
    }

    public static String getActiveModelId(Context context) {
        if (PROVIDER_GEMINI.equals(getActiveProvider(context))) {
            return getGeminiModelId(context);
        }
        return getGroqModelId(context);
    }

    public static void setActiveModelId(Context context, String modelId) {
        if (PROVIDER_GEMINI.equals(getActiveProvider(context))) {
            setGeminiModelId(context, modelId);
        } else {
            setGroqModelId(context, modelId);
        }
    }

    public static String[][] modelsForProvider(String provider) {
        return PROVIDER_GEMINI.equals(provider) ? GEMINI_MODELS : GROQ_MODELS;
    }

    public static String displayNameForModel(String provider, String modelId) {
        for (String[] entry : modelsForProvider(provider)) {
            if (entry[0].equals(modelId)) return entry[1];
        }
        return modelId;
    }

    public static String displayNameForActive(Context context) {
        String provider = getActiveProvider(context);
        String modelId = getActiveModelId(context);
        String providerLabel = PROVIDER_GEMINI.equals(provider) ? "Gemini" : "Groq";
        return providerLabel + " · " + displayNameForModel(provider, modelId);
    }

    /**
     * Modèles Groq à essayer avant le provider suivant.
     * Préféré utilisateur en tête, puis 120B si différent — sans Scout.
     */
    public static String[][] groqFallbackChain(String preferredModelId) {
        String preferred = preferredModelId != null ? preferredModelId : GROQ_GPT_OSS_120B;
        if (isRetiredGroqModel(preferred)) {
            preferred = GROQ_GPT_OSS_120B;
        }
        java.util.LinkedHashMap<String, String> ordered = new java.util.LinkedHashMap<>();
        String preferredLabel = displayNameForModel(PROVIDER_GROQ, preferred);
        ordered.put(preferred, preferredLabel);
        for (String[] entry : GROQ_ROTATION) {
            ordered.putIfAbsent(entry[0], entry[1]);
        }
        String[][] out = new String[ordered.size()][2];
        int i = 0;
        for (java.util.Map.Entry<String, String> e : ordered.entrySet()) {
            out[i][0] = e.getKey();
            out[i][1] = e.getValue();
            i++;
        }
        return out;
    }

    /** Function calling natif — cloud OpenAI-compatible (multi-provider). */
    public static boolean supportsNativeFunctionCalling(Context context) {
        if (context == null || ModelStore.useLocalLlm(context)) return false;
        return isToolCapableModel(getGroqModelId(context));
    }

    static boolean supportsNativeFunctionCalling(Context context, String modelId) {
        if (context == null || ModelStore.useLocalLlm(context)) return false;
        return isToolCapableModel(modelId);
    }

    /** Modèles capables de tool use (Groq + GPT-OSS 120B Cerebras/OpenRouter). */
    public static boolean isToolCapableModel(String modelId) {
        if (modelId == null || modelId.isEmpty()) return false;
        if (isGroqToolModel(modelId)) return true;
        if (ProviderChain.CEREBRAS_MODEL.equals(modelId)) return true;
        if (ProviderChain.OPENROUTER_MODEL.equals(modelId)) return true;
        if (modelId.contains("gpt-oss-120b")) return true;
        if (modelId.contains("llama-3.3") || modelId.contains("llama3.3")) return true;
        return false;
    }

    /** Modèles Groq avec tool use / function calling (doc Groq). */
    public static boolean isGroqToolModel(String groqModelId) {
        if (groqModelId == null || groqModelId.isEmpty()) return false;
        return GROQ_GPT_OSS_120B.equals(groqModelId)
                || GROQ_GPT_OSS_20B.equals(groqModelId)
                || GROQ_QWEN_3_32B.equals(groqModelId)
                || GROQ_QWEN_36_27B.equals(groqModelId)
                || groqModelId.startsWith("qwen/");
    }

    /** Modèles Groq Qwen — raisonnement interne à masquer côté API (fallback). */
    public static boolean isGroqQwenModel(String groqModelId) {
        return groqModelId != null && groqModelId.startsWith("qwen/");
    }
}
