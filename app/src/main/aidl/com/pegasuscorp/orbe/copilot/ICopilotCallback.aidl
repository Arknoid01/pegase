package com.pegasuscorp.orbe.copilot;

interface ICopilotCallback {
    void onScreenContextUpdated(String packageName, String text);
    void onCloudCandidate(String packageName, String text, String reason);
}
