package com.pegasuscorp.orbe.orion;

/** État runtime du pod Orion. */
public enum OrionStatus {
    OFFLINE,
    STARTING,
    READY,
    BUSY,
    STOPPING;

    /** Libellé oral court pour les réponses outil. */
    public String label() {
        switch (this) {
            case OFFLINE:
                return "hors ligne";
            case STARTING:
                return "en démarrage";
            case READY:
                return "en ligne";
            case BUSY:
                return "occupé";
            case STOPPING:
                return "en cours d'arrêt";
            default:
                return name();
        }
    }
}
