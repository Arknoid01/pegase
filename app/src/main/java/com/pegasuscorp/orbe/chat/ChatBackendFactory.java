package com.pegasuscorp.orbe.chat;

import android.content.Context;

import com.pegasuscorp.orbe.llm.ModelStore;

/**
 * Choisit le backend de discussion selon fournisseur et modèle configurés.
 */
public final class ChatBackendFactory {

    private ChatBackendFactory() {}

    public static ChatBackend create(Context context) {
        if (ModelStore.useLocalLlm(context)) {
            return new LocalLlmChatBackend(context);
        }
        // Rotation Groq → Cerebras → OpenRouter (Gemini hors chaîne)
        return new MultiProviderBackend(context);
    }
}
