package com.pegasuscorp.orbe.llm;

/**
 * Callback de génération (streaming ou bloc).
 */
public interface GenerationListener {

    void onToken(String token);

    void onComplete(String fullText);

    void onError(String error);
}
