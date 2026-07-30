package com.pegasuscorp.orbe.chat;

/**
 * Trace {@code provider_used} seulement quand le callback LLM est réellement consommé
 * (évite les orphelins quand la réponse arrive trop tard).
 */
public interface ProviderTraceSink {

    /** Écrit {@link com.pegasuscorp.orbe.diag.Trace#providerUsed} et efface l'attente. */
    void consumePendingProviderTrace();

    /** Abandonne une trace en attente (callback obsolète / erreur non consommée). */
    void discardPendingProviderTrace();
}
