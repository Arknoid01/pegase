package com.pegasuscorp.orbe.chat;

import java.util.List;

/**
 * Le "cerveau" du MODE DISCUSSION, sous forme d'interface (comme IntentParser
 * pour les commandes). MainActivity ne connait que cette interface.
 *
 * Aujourd'hui : GeminiChatBackend (API gratuite, conversationnelle, recherche web).
 * Demain : tu peux ecrire un GemmaChatBackend (LLM local) qui implemente la meme
 * interface, et router selon la difficulte de la requete. Rien d'autre a changer.
 */
public interface ChatBackend {

    /** Un tour de conversation. */
    class Turn {
        public final boolean fromUser;   // true = toi, false = le modele
        /** Hint système (ex. succès outil) — pas affiché comme bulle assistant. */
        public final boolean system;
        public final String text;

        public Turn(boolean fromUser, String text) {
            this(fromUser, false, text);
        }

        public Turn(boolean fromUser, boolean system, String text) {
            this.fromUser = fromUser;
            this.system = system;
            this.text = text != null ? text : "";
        }

        /** Contexte forcé pour le LLM (rôle system). */
        public static Turn system(String text) {
            return new Turn(false, true, text);
        }
    }

    /** Callback asynchrone (l'appel reseau ne bloque jamais l'UI). */
    interface OnReply {
        void onReply(String text);
        void onError(String error);

        /** Réponse structurée — function calling natif ou texte seul. */
        default void onLlmReply(LlmReply reply) {
            if (reply.hasNativeToolCalls()) {
                onReply(reply.content != null ? reply.content : "");
            } else {
                onReply(reply.content != null ? reply.content : "");
            }
        }
    }

    /** Streaming token-par-token (Groq SSE). */
    interface StreamOnReply extends OnReply {
        void onPartial(String accumulated);
    }

    default boolean supportsStreaming() {
        return false;
    }

    /** Identifiant pour Trace.llmReply — ex. « Groq/openai/gpt-oss-20b », pas le nom du wrapper. */
    default String traceBackendLabel() {
        return getClass().getSimpleName();
    }

    /**
     * Envoie l'historique + le nouveau message, recoit la reponse.
     * @param history      tours precedents (memoire de la conversation)
     * @param userMessage  ce que l'utilisateur vient de dire
     */
    void send(List<Turn> history, String userMessage, OnReply callback);

    default void send(List<Turn> history, String userMessage, OnReply callback,
            ChatSendOptions options) {
        send(history, userMessage, callback);
    }

    /**
     * Étape de boucle agentique — peut renvoyer du texte ou de nouveaux tool_calls.
     */
    default void sendAgenticContinuation(AgenticChain chain, ChatSendOptions options,
            OnReply callback) {
        if (chain != null && callback != null) {
            callback.onLlmReply(LlmReply.text(chain.lastToolResult()));
        }
    }

    /** Compat mono-étape. */
    default void sendAgenticContinuation(AgenticContinuation continuation, OnReply callback) {
        if (continuation != null && callback != null) {
            sendAgenticContinuation(new AgenticChain(continuation),
                    ChatSendOptions.agenticStep(ChatSendOptions.legacy().allowedTools, false),
                    callback);
        }
    }
}
