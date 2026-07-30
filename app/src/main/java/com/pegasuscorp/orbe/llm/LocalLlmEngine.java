package com.pegasuscorp.orbe.llm;

import java.util.List;

/**
 * Moteur LLM local interchangeable (llama.cpp, autre backend GGUF).
 */
public interface LocalLlmEngine {

    interface LoadCallback {
        void onLoaded();
        void onError(String error);
    }

    boolean isModelLoaded();

    void loadModel(ModelConfig config, LoadCallback callback);

    void generate(List<ChatMessage> messages, GenerationSettings settings,
                  GenerationListener listener);

    void stopGeneration();

    void unloadModel();
}
