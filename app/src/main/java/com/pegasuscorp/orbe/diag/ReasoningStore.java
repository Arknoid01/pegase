package com.pegasuscorp.orbe.diag;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Cartes de raisonnement indexées par empreinte de la réponse affichée.
 * Volatile (session) — suffisant pour l'onglet Discussion.
 */
public final class ReasoningStore {

    private static final int MAX = 48;

    private static final LinkedHashMap<String, ReasoningCard> BY_KEY =
            new LinkedHashMap<String, ReasoningCard>(MAX + 1, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, ReasoningCard> eldest) {
                    return size() > MAX;
                }
            };

    private ReasoningStore() {}

    public static synchronized void put(String replyText, ReasoningCard card) {
        if (card == null) return;
        String key = fingerprint(replyText);
        if (key.isEmpty()) return;
        BY_KEY.put(key, card);
    }

    public static synchronized ReasoningCard findForReply(String replyText) {
        String key = fingerprint(replyText);
        if (key.isEmpty()) return null;
        ReasoningCard exact = BY_KEY.get(key);
        if (exact != null) return exact;
        // Fallback : préfixe (texte nettoyé légèrement différent)
        for (Map.Entry<String, ReasoningCard> e : BY_KEY.entrySet()) {
            if (key.startsWith(e.getKey()) || e.getKey().startsWith(key)) {
                return e.getValue();
            }
        }
        return null;
    }

    public static synchronized void clear() {
        BY_KEY.clear();
    }

    /** Empreinte stable pour lier message UI ↔ carte. */
    public static String fingerprint(String replyText) {
        if (replyText == null) return "";
        String t = replyText.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        if (t.isEmpty()) return "";
        if (t.length() > 160) t = t.substring(0, 160);
        return t;
    }
}
