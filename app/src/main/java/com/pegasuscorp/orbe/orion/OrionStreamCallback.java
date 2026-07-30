package com.pegasuscorp.orbe.orion;

/** Callback streaming génération Orion / Ollama. */
public interface OrionStreamCallback {
    void onToken(String token);

    void onComplete(String full);

    void onError(String message);
}
