package com.pegasuscorp.orbe.f1companion;

import android.content.Context;
import android.util.Log;

import com.pegasuscorp.orbe.chat.ChatSessionRegistry;
import com.pegasuscorp.orbe.llm.ModelStore;

/**
 * Une phrase d'actu F1 — heuristique rapide, LLM cloud optionnel.
 */
public final class F1NewsSummarizer {

    private static final String TAG = "F1NewsSummarizer";
    private static final long LLM_TIMEOUT_SEC = 8L;

    private F1NewsSummarizer() {}

    public static String summarize(Context ctx, F1RssItem item, String teamLabel) {
        String fallback = heuristic(item, teamLabel);
        if (ctx == null || item == null) return fallback;
        if (ModelStore.useLocalLlm(ctx)) return fallback;
        try {
            String prompt =
                    "Tu es Pégase. Résume cette actu F1 en UNE seule phrase courte en français "
                    + "(max 140 caractères), ton fan, sans guillemets ni préambule. "
                    + "Équipe concernée : " + (teamLabel != null ? teamLabel : "F1") + ".\n"
                    + "Titre : " + item.title + "\n"
                    + "Extrait : " + truncate(item.description, 280);
            String out = ChatSessionRegistry.get(ctx)
                    .completeEphemeralSync(prompt, LLM_TIMEOUT_SEC, "f1_news");
            if (out == null) return fallback;
            out = out.trim().replaceAll("^[\"«]+|[\"»]+$", "").trim();
            if (out.isEmpty() || out.length() > 220) return fallback;
            // Évite les réponses méta
            String low = out.toLowerCase(java.util.Locale.ROOT);
            if (low.startsWith("désolé") || low.startsWith("je ne") || low.contains("en tant que")) {
                return fallback;
            }
            return out;
        } catch (Exception e) {
            Log.w(TAG, "LLM summary fallback", e);
            return fallback;
        }
    }

    static String heuristic(F1RssItem item, String teamLabel) {
        if (item == null) return "Nouvelle actu F1.";
        String title = item.title.trim();
        if (title.isEmpty() && item.description != null && !item.description.isEmpty()) {
            title = item.description.trim();
        }
        title = title.replaceAll("\\s+", " ");
        if (title.length() > 140) title = title.substring(0, 137) + "…";
        if (teamLabel != null && !teamLabel.isEmpty()
                && !title.toLowerCase(java.util.Locale.ROOT)
                .contains(teamLabel.toLowerCase(java.util.Locale.ROOT))) {
            return teamLabel + " — " + title;
        }
        return title;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "…";
    }
}
