package com.pegasuscorp.orbe.voice;

/**
 * Callback launcher ← processus :voice — wake word détecté.
 * RemoteCallbackList côté VoiceService.
 */
oneway interface IWakeWordCallback {
    void onWakeWordDetected(String command);
}
