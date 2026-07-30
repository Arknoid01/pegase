package com.pegasuscorp.orbe.orion;

/**
 * Décide quoi faire d'un prompt Bureau en attente selon l'état du pod.
 */
public final class OrionBureauBridge {

    public enum Action {
        /** Rien à faire. */
        NONE,
        /** Envoyer le prompt maintenant. */
        SUBMIT,
        /** Proposer / attendre le démarrage, garder le prompt en file. */
        LAUNCH_AND_WAIT,
        /** Pod démarre ou occupé — garder en file. */
        WAIT
    }

    private OrionBureauBridge() {}

    public static Action decide(OrionStatus status, boolean hasPending, boolean generating) {
        if (!hasPending) return Action.NONE;
        if (generating) return Action.WAIT;
        if (status == null) return Action.LAUNCH_AND_WAIT;
        switch (status) {
            case READY:
                return Action.SUBMIT;
            case STARTING:
            case BUSY:
            case STOPPING:
                return Action.WAIT;
            case OFFLINE:
            default:
                return Action.LAUNCH_AND_WAIT;
        }
    }
}
