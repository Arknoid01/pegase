package com.pegasuscorp.orbe.llm;

import android.content.Context;

/**
 * Point d'accès unique au moteur LLM local.
 */
public final class LlmEngineManager {

    private static LlmEngineManager instance;

    private final LocalLlmEngine engine;
    private ModelConfig loadedConfig;

    private LlmEngineManager() {
        engine = new LlamaCppEngine();
    }

    public static synchronized LlmEngineManager getInstance() {
        if (instance == null) instance = new LlmEngineManager();
        return instance;
    }

    public LocalLlmEngine getEngine() {
        return engine;
    }

    public ModelConfig getLoadedConfig() {
        return loadedConfig;
    }

    public void loadActiveModel(Context context, LocalLlmEngine.LoadCallback callback) {
        ModelConfig config = ModelStore.resolveActiveConfig(context);
        if (config == null) {
            callback.onError("Aucun fichier GGUF trouvé. Place un modèle dans "
                    + ModelStore.modelsDir(context).getAbsolutePath()
                    + " ou importe un fichier.");
            return;
        }
        engine.loadModel(config, new LocalLlmEngine.LoadCallback() {
            @Override
            public void onLoaded() {
                loadedConfig = config;
                callback.onLoaded();
            }

            @Override
            public void onError(String error) {
                loadedConfig = null;
                callback.onError(error);
            }
        });
    }

    public void reloadActiveModel(Context context, LocalLlmEngine.LoadCallback callback) {
        engine.unloadModel();
        loadedConfig = null;
        loadActiveModel(context, callback);
    }

    /** Libère le GGUF si chargé (pression mémoire / idle). */
    public void unloadIfLoaded() {
        if (loadedConfig == null && !engine.isModelLoaded()) return;
        engine.unloadModel();
        loadedConfig = null;
    }
}
