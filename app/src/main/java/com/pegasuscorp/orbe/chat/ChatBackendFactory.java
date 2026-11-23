package com.pegasuscorp.orbe.chat;

import android.content.Context;

import com.pegasuscorp.orbe.llm.ModelStore;

/**
 * Choisit le backend de discussion selon fournisseur et modèle configurés.
 */
public final class ChatBackendFactory {

    private static volatile ChatBackend sharedCloudBackend;
    private static volatile ChatBackend sharedLocalBackend;
    private static volatile ChatBackend sharedSwitch;

    private ChatBackendFactory() {}

    /** Initialise les singletons discussion si besoin. */
    private static void ensureDiscussionBackends(Context app) {
        if (sharedSwitch == null) {
            synchronized (ChatBackendFactory.class) {
                if (sharedSwitch == null) {
                    sharedCloudBackend = new MultiProviderBackend(app);
                    sharedLocalBackend = new LocalServerChatBackend(app);
                    sharedSwitch = new LocalOnlySwitchBackend(app, sharedCloudBackend,
                            sharedLocalBackend);
                }
            }
        }
    }

    public static ChatBackend create(Context context) {
        Context app = context.getApplicationContext();
        if (ModelStore.useLocalLlm(app)) {
            return new LocalLlmChatBackend(app);
        }
        ensureDiscussionBackends(app);
        return sharedSwitch;
    }

    /** Cloud Groq/Cerebras/OpenRouter — pour ombre d'entraînement en mode « Toujours local ». */
    public static ChatBackend cloudDiscussion(Context context) {
        Context app = context.getApplicationContext();
        ensureDiscussionBackends(app);
        return sharedCloudBackend;
    }

    /** Tests unitaires — réinitialise le singleton cloud. */
    public static void resetForTests() {
        sharedCloudBackend = null;
        sharedLocalBackend = null;
        sharedSwitch = null;
    }
}
