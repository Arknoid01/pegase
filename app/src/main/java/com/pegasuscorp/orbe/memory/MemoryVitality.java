package com.pegasuscorp.orbe.memory;

/**
 * Oubli naturel et renforcement des souvenirs permanents — symétrique au graphe entités.
 */
public final class MemoryVitality {

    /** Jours sans récupération avant le début de l'oubli. */
    static final double GRACE_DAYS = 14;
    /** Demi-vie de l'oubli (jours au-delà de la grâce). */
    static final double DECAY_HALF_LIFE_DAYS = 365;
    static final double MIN_IMPORTANCE = 0.15;
    static final double RETRIEVAL_STRENGTHEN = 0.02;

    private MemoryVitality() {}

    static double decayedImportance(double importanceAtLastUse, long lastUsedAtMs, long nowMs,
            boolean frozen) {
        if (frozen || importanceAtLastUse <= MIN_IMPORTANCE) return importanceAtLastUse;
        double daysUnused = (nowMs - lastUsedAtMs) / 86_400_000.0;
        if (daysUnused <= GRACE_DAYS) return importanceAtLastUse;
        double excessDays = daysUnused - GRACE_DAYS;
        double factor = Math.pow(0.5, excessDays / DECAY_HALF_LIFE_DAYS);
        return clamp(importanceAtLastUse * factor);
    }

    static double clamp(double value) {
        if (value < MIN_IMPORTANCE) return MIN_IMPORTANCE;
        if (value > 1) return 1;
        return value;
    }

    static boolean defaultFrozen(String category) {
        return "project".equals(category);
    }
}
