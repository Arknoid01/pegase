package com.pegasuscorp.orbe.copilot;

import android.content.Context;
import android.text.TextUtils;

/**
 * Prompt et encodage du plan interne copilote (éphémère, hors bulle utilisateur).
 */
public final class CopilotReflectionPlanner {

    static final String PAYLOAD_MARKER = "[Plan interne — ne pas répéter à l'utilisateur]";

    private CopilotReflectionPlanner() {}

    /** Prompt pour l'appel LLM éphémère de planification. */
    public static String buildReflectionPrompt(CopilotScreenContext.Snapshot snap,
            String userText) {
        return buildReflectionPrompt(null, snap, userText);
    }

    /** @param ctx pour charger seeds + overrides hints (sinon builtins via package seul). */
    public static String buildReflectionPrompt(Context ctx,
            CopilotScreenContext.Snapshot snap, String userText) {
        StringBuilder sb = new StringBuilder();
        sb.append("Tu es le planificateur interne du copilote Pégase.\n");
        sb.append("L'utilisateur voit une autre app ; tu reçois un extrait d'écran local.\n");
        sb.append("Produis un PLAN COURT (4 à 8 puces), en français, sans markdown.\n");
        sb.append("Couvre : éléments visibles pertinents, intention utilisateur, ");
        sb.append("action éventuelle, incertitudes, forme de la réponse finale.\n");
        sb.append("NE RÉPONDS PAS à l'utilisateur — plan interne seulement.\n\n");
        if (snap != null) {
            CopilotAppHints hints = null;
            if (!snap.packageName.isEmpty()) {
                hints = ctx != null
                        ? CopilotAppHintsStore.get(ctx, snap.packageName)
                        : CopilotAppHintsStore.builtin(snap.packageName);
            }
            String block = CopilotScreenContext.buildPromptBlock(snap, hints).trim();
            if (!block.isEmpty()) sb.append(block).append("\n\n");
        }
        sb.append("Demande utilisateur : ").append(userText != null ? userText.trim() : "");
        return sb.toString().trim();
    }

    /** Préfixe injecté dans le payload envoyé au tour visible. */
    public static String buildPayloadPrefix(String plan) {
        if (TextUtils.isEmpty(plan)) return "";
        return PAYLOAD_MARKER + "\n" + plan.trim() + "\n\n";
    }
}
