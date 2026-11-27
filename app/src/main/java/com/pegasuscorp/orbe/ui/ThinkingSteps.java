package com.pegasuscorp.orbe.ui;

/**
 * Étapes réelles de la réflexion (chatbot, web, souvenirs, modèle…) envoyées depuis le fil de
 * travail vers la ligne « réflexion » de la discussion. Un seul auditeur : la discussion visible.
 */
public final class ThinkingSteps {

    public interface Listener {
        void onStep(String step);
    }

    private static volatile Listener listener;

    private ThinkingSteps() {}

    public static void setListener(Listener l) {
        listener = l;
    }

    public static void clearListener(Listener l) {
        if (listener == l) listener = null;
    }

    /** Depuis n'importe quel fil. */
    public static void post(String step) {
        Listener l = listener;
        if (l != null && step != null && !step.trim().isEmpty()) l.onStep(step.trim());
    }
}
