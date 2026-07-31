package com.pegasuscorp.orbe.voice;

/**
 * Calcule l'état wake affiché — testable sans {@link VoiceService}.
 */
public final class WakeHealthEvaluator {

    private WakeHealthEvaluator() {}

    public static WakeHealthStatus evaluate(boolean wantListening, boolean kwsTripped,
            boolean kwsRunning, boolean modelReady) {
        if (!wantListening) return WakeHealthStatus.OFF;
        if (kwsTripped) return WakeHealthStatus.PROBLEM;
        if (kwsRunning) return WakeHealthStatus.LISTENING;
        if (modelReady) return WakeHealthStatus.PROBLEM;
        return WakeHealthStatus.LISTENING;
    }
}
