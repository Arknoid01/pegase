package com.pegasuscorp.orbe.voice;

import com.pegasuscorp.orbe.voice.IWakeWordCallback;
import com.pegasuscorp.orbe.voice.IWakeHealthCallback;

/**
 * API wake word exposée par {@code VoiceService} (processus {@code :voice}).
 * Le launcher ne lit aucun singleton voix : il pousse start/stop.
 */
interface IVoiceWakeService {
    void startWakeListening();
    void stopWakeListening();
    /**
     * Stoppe le KWS sans couper le SCO — handoff wake→STT / conversation.
     * Le SCO reste tenu jusqu'à {@link #stopWakeListening} ou un timeout service.
     */
    void pauseWakeListeningKeepSco();
    void setGentleMode(boolean gentle);
    /** Seuil openWakeWord — poussé depuis le launcher (:voice a ses propres prefs). */
    void setOwwThreshold(float threshold);
    void registerCallback(IWakeWordCallback callback);
    void unregisterCallback(IWakeWordCallback callback);
    void registerHealthCallback(IWakeHealthCallback callback);
    void unregisterHealthCallback(IWakeHealthCallback callback);
    int getWakeHealthCode();
    void resetKwsCrashGuard();
    /**
     * Launcher : session STT conversation démarrée (HANDING_OFF → STT_ACTIVE).
     */
    void notifySttSessionStarted();
    /**
     * Launcher : fin de session STT (STT_ACTIVE → rearm LISTENING_WAKE).
     */
    void notifySttSessionEnded();
}
