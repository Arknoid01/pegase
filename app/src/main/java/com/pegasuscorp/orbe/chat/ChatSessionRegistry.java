package com.pegasuscorp.orbe.chat;

import android.content.Context;

/**
 * Session de discussion partagée — survit à la destruction de {@link com.pegasuscorp.orbe.MainActivity}
 * tant que l'interface Pégase est ouverte.
 */
public final class ChatSessionRegistry {

    private static ConversationManager conversation;

    private ChatSessionRegistry() {}

    public static synchronized ConversationManager get(Context context) {
        if (conversation == null) {
            conversation = new ConversationManager(context, ChatBackendFactory.create(context));
        }
        return conversation;
    }

    public static synchronized ConversationManager recreate(Context context) {
        conversation = new ConversationManager(context, ChatBackendFactory.create(context));
        return conversation;
    }

    public static boolean isActive() {
        return conversation != null && conversation.isActive();
    }

    /** Termine la session et persiste la mémoire. @return true si des échanges ont été sauvegardés */
    public static synchronized boolean finalizeSession() {
        if (conversation == null || !conversation.isActive()) return false;
        return conversation.exit();
    }
}
