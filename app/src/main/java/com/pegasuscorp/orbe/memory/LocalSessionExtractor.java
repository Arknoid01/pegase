package com.pegasuscorp.orbe.memory;

import com.pegasuscorp.orbe.chat.ChatBackend;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Extraction heuristique locale (sans LLM) des faits, décisions et sujets en attente
 * depuis les tours utilisateur d'une session.
 */
public final class LocalSessionExtractor {

    private static final int MAX_ITEM_CHARS = 120;

    private static final String[] FACT_MARKERS = {
            "retiens que", "note que", "n'oublie pas", "il faut savoir", "important",
            "j'aime", "je préfère", "je prefere", "mon projet", "ma femme", "mon téléphone",
            "mon telephone", "je m'appelle", "j'habite", "je travaille", "je développe",
            "je developpe", "mon objectif", "ma préférence", "ma preference"
    };

    private static final String[] DECISION_MARKERS = {
            "d'accord pour", "on part sur", "c'est décidé", "c'est decide", "je choisis",
            "validé", "valide", "on fait comme", "ok pour", "on valide", "décidé de",
            "decide de", "on garde", "je prends"
    };

    // PENDING_MARKERS retiré : isDurablePending (radicaux) est la source de vérité.

    private LocalSessionExtractor() {}

    public static void enrich(SessionSummary summary, List<ChatBackend.Turn> turns) {
        if (summary == null || turns == null || turns.isEmpty()) return;
        Set<String> seen = new HashSet<>();
        // Pré-remplir seen avec l'existant (évite doublons après résumé LLM)
        for (String p : summary.pendingTopics) {
            if (p != null) seen.add(p.toLowerCase(Locale.ROOT));
        }
        for (String d : summary.decisions) {
            if (d != null) seen.add(d.toLowerCase(Locale.ROOT));
        }
        for (String f : summary.importantFacts) {
            if (f != null) seen.add(f.toLowerCase(Locale.ROOT));
        }
        for (ChatBackend.Turn turn : turns) {
            if (turn == null || !turn.fromUser || turn.text == null) continue;
            String text = turn.text.trim();
            if (text.length() < 8) continue;
            String lower = text.toLowerCase(Locale.ROOT);
            // Pending d'abord via radicaux (pas une liste de phrases figées)
            if (EphemeralMemoryFilter.isDurablePending(text)) {
                addUnique(summary.pendingTopics, clip(text), seen);
            } else if (containsAny(lower, DECISION_MARKERS)
                    && EphemeralMemoryFilter.isDurableSessionItem(text)) {
                addUnique(summary.decisions, clip(text), seen);
            } else if (containsAny(lower, FACT_MARKERS)
                    && EphemeralMemoryFilter.isDurableSessionItem(text)) {
                addUnique(summary.importantFacts, clip(text), seen);
            }
        }
    }

    private static boolean containsAny(String lower, String[] markers) {
        for (String marker : markers) {
            if (lower.contains(marker)) return true;
        }
        return false;
    }

    private static void addUnique(List<String> target, String item, Set<String> seen) {
        if (item == null || item.isEmpty()) return;
        String key = item.toLowerCase(Locale.ROOT);
        if (!seen.add(key)) return;
        target.add(item);
    }

    private static String clip(String text) {
        if (text.length() <= MAX_ITEM_CHARS) return text;
        return text.substring(0, MAX_ITEM_CHARS - 1) + "…";
    }
}
