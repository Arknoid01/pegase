package com.pegasuscorp.orbe.tools;

import android.app.Activity;

import java.util.EnumSet;

/**
 * Point d'entrée unique : détecte un appel d'outil dans la réponse LLM et l'exécute.
 */
public final class ToolOrchestrator {

    public interface ReplyHandler {
        void onSpokenReply(String text);
        /** Réponse après un outil qui a ouvert une autre app — suspend la conversation. */
        default void onExitReply(String text) { onSpokenReply(text); }
        void onError(String error);
    }

    private final ToolDispatcher dispatcher = new ToolDispatcher(new ToolRegistry());

    /**
     * Si {@code text} est un appel d'outil JSON, l'exécute et retourne true.
     * Sinon retourne false — l'appelant traite le texte comme une réponse normale.
     */
    public boolean handleIfToolCall(Activity activity, String text, ReplyHandler handler) {
        if (ToolDispatcher.isToolCall(text)) {
            dispatchTool(activity, text, handler);
            return true;
        }
        if (ToolDispatcher.looksLikeToolAttempt(text)) {
            activity.runOnUiThread(() -> handler.onError(
                    "Je n'ai pas pu exécuter l'outil — le format était incorrect. Réessaie."));
            return true;
        }
        return false;
    }

    private void dispatchTool(Activity activity, String text, ReplyHandler handler) {
        dispatcher.dispatch(activity, text, new ToolCallback() {
            @Override
            public void onSuccess(ToolResult result) {
                String spoken = result != null ? result.wireText() : "";
                activity.runOnUiThread(() -> handler.onSpokenReply(spoken));
            }

            @Override
            public void onSuccessAndExit(ToolResult result) {
                String spoken = result != null ? result.wireText() : "";
                activity.runOnUiThread(() -> handler.onExitReply(spoken));
            }

            @Override
            public void onConfirmNeeded(String question, Runnable onConfirm, Runnable onCancel) {
                com.pegasuscorp.orbe.session.ChatConfirmBridge.askConfirm(
                        activity, question, onConfirm, onCancel);
            }

            @Override
            public void onChoiceNeeded(String title, String[] labels,
                    java.util.function.IntConsumer onChosen, Runnable onCancel) {
                com.pegasuscorp.orbe.session.ChatConfirmBridge.askChoice(
                        activity, title, labels, onChosen, onCancel);
            }

            @Override
            public void onError(String error) {
                activity.runOnUiThread(() -> handler.onError(error));
            }
        });
    }

    /** Section outils pour le prompt système (tous les outils). */
    public static String promptSection() {
        return new ToolRegistry().buildPromptSection();
    }

    /** Section outils filtrée selon l'intention détectée. */
    public static String promptSection(EnumSet<ToolTag> allowedTools) {
        return new ToolRegistry().buildPromptSection(allowedTools);
    }
}
