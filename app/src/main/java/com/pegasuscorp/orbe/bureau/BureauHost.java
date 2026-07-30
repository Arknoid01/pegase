package com.pegasuscorp.orbe.bureau;

/**
 * Hôte bureau pour le relais micro ({@link com.pegasuscorp.orbe.chat.ChatVoiceBridge}).
 * Partagé entre le bureau Markdown (actif) et le canvas (parké).
 */
public interface BureauHost {
    void handleBureauVoice(String transcript);

    void runOnUiThread(Runnable action);
}
