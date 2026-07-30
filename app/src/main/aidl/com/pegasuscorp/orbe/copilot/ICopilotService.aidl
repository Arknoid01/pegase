package com.pegasuscorp.orbe.copilot;

import com.pegasuscorp.orbe.copilot.ICopilotCallback;

/**
 * API analyse copilote exposée par {@link CopilotService} (processus {@code :copilot}).
 */
interface ICopilotService {
    void startAnalysis();
    void stopAnalysis();
    boolean isScreenOn();
    String getLastScreenText();
    void registerCallback(ICopilotCallback callback);
    void unregisterCallback(ICopilotCallback callback);
}
