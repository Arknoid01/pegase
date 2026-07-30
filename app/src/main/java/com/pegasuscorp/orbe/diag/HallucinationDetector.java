package com.pegasuscorp.orbe.diag;

import java.util.Locale;

/**
 * Détecte les affirmations sur le passé sans source (RAG / outil fiable).
 * Partagé bureau + chat (ReasoningCard).
 */
public final class HallucinationDetector {

    private HallucinationDetector() {}

    /** Patterns (texte déjà foldé, accents aplatis). */
    private static final String[] PAST_TRIGGERS_FOLDED = {
            "on avait essaye",
            "on a deja essaye",
            "on a deja fait",
            "j'avais note",
            "javais note",
            "tu m'avais dit",
            "tu mavais dit",
            "on avait decide",
            "on avait abandonne",
            "j'avais mentionne",
            "javais mentionne",
            "la derniere fois",
            "tu avais essaye",
            "on avait teste",
    };

    /**
     * @param contextChunks chunks RAG injectés (&gt;0 = source réelle)
     * @param hasReliableSource outil réussi (calc, search, wiki…) ou mémoire injectée
     */
    public static boolean isPotentialHallucination(String reply, int contextChunks,
            boolean hasReliableSource) {
        if (contextChunks > 0 || hasReliableSource) return false;
        String matched = matchedTrigger(reply);
        return matched != null;
    }

    public static boolean isPotentialHallucination(String reply, int contextChunks) {
        return isPotentialHallucination(reply, contextChunks, false);
    }

    /** Raison affichable, ou null si pas d'hallucination. */
    public static String reason(String reply, int contextChunks, boolean hasReliableSource) {
        if (contextChunks > 0 || hasReliableSource) return null;
        String matched = matchedTrigger(reply);
        if (matched == null) return null;
        return "Aucune source — affirmation sur le passé (« " + matched + " ») sans contexte RAG";
    }

    static String matchedTrigger(String reply) {
        if (reply == null || reply.isEmpty()) return null;
        String f = fold(reply);
        for (String trigger : PAST_TRIGGERS_FOLDED) {
            if (f.contains(trigger)) {
                return trigger;
            }
        }
        return null;
    }

    public static String fold(String text) {
        if (text == null) return "";
        return text.toLowerCase(Locale.ROOT)
                .replace('é', 'e').replace('è', 'e').replace('ê', 'e')
                .replace('à', 'a').replace('â', 'a')
                .replace('ô', 'o').replace('ù', 'u').replace('û', 'u')
                .replace('ç', 'c')
                .replace('’', '\'')
                .replace('\u2019', '\'');
    }
}
