package com.pegasuscorp.orbe.llm;

/**
 * Paramètres de génération pour une requête.
 */
public final class GenerationSettings {

    public final float temperature;
    public final float topP;
    public final int maxTokens;
    public final boolean streaming;

    public GenerationSettings(float temperature, float topP, int maxTokens, boolean streaming) {
        this.temperature = temperature;
        this.topP = topP;
        this.maxTokens = maxTokens;
        this.streaming = streaming;
    }

    public static GenerationSettings defaults() {
        return new GenerationSettings(0.75f, 0.9f, 200, true);
    }
}
