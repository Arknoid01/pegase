package com.pegasuscorp.orbe.tools;

/**
 * Résultat d'exécution d'un outil, livré sur le thread principal.
 */
public interface ToolCallback {

    /** Outil exécuté — {@link ToolResult#text} sera dit à voix haute si pertinent. */
    void onSuccess(ToolResult result);

    /**
     * Outil exécuté ET il a ouvert une autre app (premier plan changé).
     * En session vocale, la conversation continue : confirmation TTS puis reprise micro.
     */
    default void onSuccessAndExit(ToolResult result) {
        onSuccess(result);
    }

    /** L'outil a besoin d'une confirmation visuelle avant d'agir. */
    void onConfirmNeeded(String question, Runnable onConfirm, Runnable onCancel);

    /**
     * Choix parmi plusieurs options (ex. GPU Orion).
     * @param title   titre de la boîte de dialogue
     * @param labels  libellés (index 0 = premier)
     * @param onChosen reçoit l'index choisi (0-based)
     * @param onCancel annulation
     */
    default void onChoiceNeeded(String title, String[] labels,
            java.util.function.IntConsumer onChosen, Runnable onCancel) {
        if (onCancel != null) onCancel.run();
    }

    /** Progression longue (ex. démarrage Orion) — optionnel. */
    default void onProgress(String message) {}

    /** Échec — message d'erreur à dire à voix haute. */
    void onError(String error);
}
