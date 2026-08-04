package com.pegasuscorp.orbe.contextstore;

import android.content.Context;
import android.text.TextUtils;

import com.pegasuscorp.orbe.llm.ModelStore;

/**
 * Colle les contextes .md chargés juste avant le message utilisateur.
 * Les mettre dans le system (après le pavé d'outils) fait souvent ignorer / tronquer
 * le document — surtout en LLM local (n_ctx ≈ 4k).
 */
public final class AttachedContextInjector {

    /** Budget cloud : assez pour un brief / spec. */
    public static final int MAX_CHARS_CLOUD = 14_000;
    /** Budget local : laisse de la place aux outils + historique. */
    public static final int MAX_CHARS_LOCAL = 3_500;

    private AttachedContextInjector() {}

    public static String wrapUserMessage(Context context, String userMessage) {
        return wrapUserMessage(context, userMessage, -1);
    }

    /**
     * @param maxAttachedChars &lt; 0 = budget cloud/local par défaut ; sinon plafond explicite.
     */
    public static String wrapUserMessage(Context context, String userMessage,
            int maxAttachedChars) {
        if (context == null) return userMessage != null ? userMessage : "";
        String msg = userMessage != null ? userMessage : "";
        ContextualFileStore store = ContextualFileStore.getInstance(context);
        if (store.getLoadedFilenames().isEmpty()) return msg;

        int budget = ModelStore.useLocalLlm(context) ? MAX_CHARS_LOCAL : MAX_CHARS_CLOUD;
        if (maxAttachedChars > 0) budget = Math.min(budget, maxAttachedChars);
        String docs = store.buildPromptSection(budget);
        if (TextUtils.isEmpty(docs)) return msg;

        return docs.trim()
                + "\n\n=== Message de l'utilisateur ===\n"
                + msg;
    }
}
