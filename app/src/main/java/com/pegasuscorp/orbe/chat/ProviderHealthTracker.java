package com.pegasuscorp.orbe.chat;

import java.util.HashMap;
import java.util.Map;

/**
 * Cool-down après timeout / 429 pour éviter de marteler un provider mort.
 */
public final class ProviderHealthTracker {

    public static final long COOLDOWN_MS = 30_000L;
    public static final long RATELIMIT_COOLDOWN_MS = 60_000L;

    private static final class Health {
        long unavailableUntilMs;
    }

    private final Map<String, Health> status = new HashMap<>();
    private final Clock clock;

    public interface Clock {
        long nowMs();
    }

    public ProviderHealthTracker() {
        this(System::currentTimeMillis);
    }

    public ProviderHealthTracker(Clock clock) {
        this.clock = clock != null ? clock : System::currentTimeMillis;
    }

    public synchronized boolean isUnhealthy(LlmProvider p) {
        if (p == null) return true;
        Health h = status.get(p.id);
        if (h == null) return false;
        return clock.nowMs() < h.unavailableUntilMs;
    }

    public synchronized void markTimeout(LlmProvider p) {
        setUnavailable(p, COOLDOWN_MS);
    }

    public synchronized void markRateLimit(LlmProvider p, long retryAfterMs) {
        setUnavailable(p, Math.max(retryAfterMs, RATELIMIT_COOLDOWN_MS));
    }

    public synchronized void markError(LlmProvider p) {
        setUnavailable(p, COOLDOWN_MS);
    }

    public synchronized void markSuccess(LlmProvider p) {
        if (p != null) status.remove(p.id);
    }

    public synchronized void reset() {
        status.clear();
    }

    private void setUnavailable(LlmProvider p, long durationMs) {
        if (p == null) return;
        Health h = status.get(p.id);
        if (h == null) {
            h = new Health();
            status.put(p.id, h);
        }
        h.unavailableUntilMs = clock.nowMs() + Math.max(1L, durationMs);
    }
}
