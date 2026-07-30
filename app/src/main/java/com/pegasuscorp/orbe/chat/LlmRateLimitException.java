package com.pegasuscorp.orbe.chat;

/** 429 — bascule multi-provider plutôt que d'attendre longtemps. */
public final class LlmRateLimitException extends Exception {
    public final long retryAfterMs;
    public final String providerId;

    public LlmRateLimitException(String providerId, long retryAfterMs) {
        super("Rate limit " + (providerId != null ? providerId : "llm")
                + " — réessai dans " + retryAfterMs + " ms");
        this.providerId = providerId;
        this.retryAfterMs = Math.max(0L, retryAfterMs);
    }
}
