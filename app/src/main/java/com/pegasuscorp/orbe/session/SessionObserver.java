package com.pegasuscorp.orbe.session;

import com.pegasuscorp.orbe.tools.ToolResult;

/** Abonné aux événements PegaseSession — plusieurs surfaces peuvent écouter (Q4). */
public interface SessionObserver {

    void onReply(String text, boolean toolFired);

    void onToolResult(ToolResult result);

    void onError(String message);

    /** Tokens cumulés pendant un envoi streamé (canal voix). */
    default void onPartial(String accumulated) {}

    /**
     * Progression outil (Orion démarrage, etc.) — ne finalise pas le tour.
     * Affichage / toast léger, pas de TTS complet.
     */
    default void onToolProgress(String message) {}

    /** Outil qui ouvre une autre app — pas de reprise micro automatique. */
    default void onToolExit(ToolResult result) {}

    /** Outil LLM refusé (ex. écran verrouillé). */
    default void onToolBlocked() {}

    /** Début d'exécution d'un outil (ThinkingView). */
    default void onToolStart(String toolId) {}

    /** Fin d'exécution d'un outil (ThinkingView). */
    default void onToolEnd(String toolId, boolean ok) {}

    /** Le LLM génère / réfléchit (ThinkingView). */
    default void onLlmStart() {}

    /**
     * Confirmation avant action coûteuse / destructive.
     * @return true si l'UI / la voix a pris en charge la question
     */
    default boolean onConfirmNeeded(String question, Runnable onConfirm, Runnable onCancel) {
        return false;
    }

    /**
     * Choix parmi plusieurs options (ex. GPU Orion).
     * @return true si l'UI a pris en charge
     */
    default boolean onChoiceNeeded(String title, String[] labels,
            java.util.function.IntConsumer onChosen, Runnable onCancel) {
        return false;
    }

    /** Faux pour bloquer l'exécution d'un outil détecté dans une réponse LLM. */
    default boolean allowToolExecution() {
        return true;
    }
}
