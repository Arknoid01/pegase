package com.pegasuscorp.orbe.voice;

/**
 * Phase visuelle partagée — écoute micro vs réflexion assistant (P5 v3).
 */
public enum PegaseVisualPhase {
    IDLE,
    MIC_LISTENING,
    THINKING;

    public boolean isListening() {
        return this == MIC_LISTENING;
    }

    public boolean isThinking() {
        return this == THINKING;
    }
}
