package com.pegasuscorp.orbe.voice;

/**
 * État honnête du wake — notif FGS + orbe HOME (P4 v3).
 */
public enum WakeHealthStatus {
    /** Wake non demandé (stop / désactivé côté launcher). */
    OFF(0),
    /** KWS actif ou démarrage en cours. */
    LISTENING(1),
    /** Coupe-circuit KWS ou écoute impossible malgré la demande. */
    PROBLEM(2);

    public final int code;

    WakeHealthStatus(int code) {
        this.code = code;
    }

    public static WakeHealthStatus fromCode(int code) {
        for (WakeHealthStatus s : values()) {
            if (s.code == code) return s;
        }
        return OFF;
    }

    public boolean isProblem() {
        return this == PROBLEM;
    }
}
