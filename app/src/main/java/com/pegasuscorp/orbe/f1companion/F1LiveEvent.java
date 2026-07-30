package com.pegasuscorp.orbe.f1companion;

/**
 * Événement live rare à pousser pendant un Grand Prix.
 */
public final class F1LiveEvent {

    public enum Kind {
        SAFETY_CAR,
        VSC,
        RED_FLAG,
        RETIREMENT,
        PENALTY,
        CHEQUERED,
        BIG_MOVE
    }

    public final Kind kind;
    public final String id;
    public final String title;
    public final String body;
    public final String teamLabel;
    public final int priority;
    public final long atMs;

    public F1LiveEvent(Kind kind, String id, String title, String body,
            String teamLabel, int priority, long atMs) {
        this.kind = kind;
        this.id = id != null ? id : "";
        this.title = title != null ? title : "Pégase · Live F1";
        this.body = body != null ? body : "";
        this.teamLabel = teamLabel != null ? teamLabel : "";
        this.priority = priority;
        this.atMs = atMs;
    }
}
