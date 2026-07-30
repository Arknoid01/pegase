package com.pegasuscorp.orbe.llm;

import android.content.Context;

import java.io.File;

/**
 * Préférences du modèle LLM actif.
 */
public final class ModelStore {

    public static final String PRESET_QWEN_4B = "qwen3_4b";
    public static final String PRESET_QWEN_17B = "qwen3_1_7b";
    public static final String PRESET_CUSTOM = "custom";

    public static final String FILE_QWEN_4B = "Qwen3-4B-Q4_K_M.gguf";
    public static final String FILE_QWEN_17B = "Qwen3-1.7B-Q4_K_M.gguf";

    private static final String PREFS = "orbe_llm";
    private static final String KEY_PRESET = "model_preset";
    private static final String KEY_CUSTOM_PATH = "custom_model_path";
    private static final String KEY_USE_LOCAL = "use_local_llm";

    private ModelStore() {}

    public static boolean useLocalLlm(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_USE_LOCAL, false);  // false = Gemini par défaut
    }

    public static void setUseLocalLlm(Context context, boolean useLocal) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_USE_LOCAL, useLocal)
                .apply();
    }

    public static String getActivePreset(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_PRESET, PRESET_QWEN_4B);
    }

    public static void setActivePreset(Context context, String preset) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_PRESET, preset)
                .apply();
    }

    public static String getCustomModelPath(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_CUSTOM_PATH, "");
    }

    public static void setCustomModelPath(Context context, String path) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_CUSTOM_PATH, path == null ? "" : path)
                .apply();
    }

    public static File modelsDir(Context context) {
        File dir = new File(context.getFilesDir(), "models");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public static ModelConfig resolveActiveConfig(Context context) {
        String preset = getActivePreset(context);
        switch (preset) {
            case PRESET_QWEN_17B:
                return presetConfig(context, FILE_QWEN_17B, "Qwen3 1.7B");
            case PRESET_CUSTOM: {
                String custom = getCustomModelPath(context);
                if (custom == null || custom.isEmpty()) return null;
                File f = new File(custom);
                if (!f.exists()) return null;
                return new ModelConfig(custom, f.getName());
            }
            case PRESET_QWEN_4B:
            default:
                return presetConfig(context, FILE_QWEN_4B, "Qwen3 4B");
        }
    }

    private static ModelConfig presetConfig(Context context, String filename, String label) {
        File file = new File(modelsDir(context), filename);
        if (!file.exists()) return null;
        return new ModelConfig(file.getAbsolutePath(), label);
    }

    public static String displayNameForPreset(String preset) {
        switch (preset) {
            case PRESET_QWEN_17B: return "Qwen3 1.7B";
            case PRESET_CUSTOM: return "Fichier GGUF personnalisé";
            case PRESET_QWEN_4B:
            default: return "Qwen3 4B";
        }
    }
}
