package com.pegasuscorp.orbe.llm;

/**
 * Configuration d'un modèle GGUF interchangeable.
 */
public final class ModelConfig {

    public final String path;
    public final String displayName;
    public final int contextSize;
    public final int threads;
    public final float temperature;
    public final float topP;
    public final int maxTokens;

    public ModelConfig(String path, String displayName) {
        this(path, displayName, 2048, 6, 0.75f, 0.9f, 200);
    }

    public ModelConfig(String path, String displayName, int contextSize, int threads,
                     float temperature, float topP, int maxTokens) {
        this.path = path;
        this.displayName = displayName;
        this.contextSize = contextSize;
        this.threads = threads;
        this.temperature = temperature;
        this.topP = topP;
        this.maxTokens = maxTokens;
    }

    public GenerationSettings toGenerationSettings() {
        return new GenerationSettings(temperature, topP, maxTokens, true);
    }
}
