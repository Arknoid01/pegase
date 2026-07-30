package com.pegasuscorp.orbe.memory;

import android.content.Context;

import com.pegasuscorp.orbe.chat.ChatBackend;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Sélectionne l'historique envoyé au LLM : récent + pertinence, pas tout le buffer. */
public final class ConversationHistorySelector {

    public static final int RECENT_TURN_LIMIT = 6;
    private static final int MAX_EXTRA_RELEVANT = 2;
    private static final int MIN_WORD_LEN = 4;

    private ConversationHistorySelector() {}

    public static List<ChatBackend.Turn> selectForPrompt(Context context,
            List<ChatBackend.Turn> fullHistory, String userMessage) {
        if (fullHistory == null || fullHistory.isEmpty()) {
            return Collections.emptyList();
        }
        List<ChatBackend.Turn> cleaned = ConversationHistorySanitizer.stripPoisonTurns(fullHistory);
        if (cleaned.isEmpty()) return Collections.emptyList();
        int recentStart = Math.max(0, cleaned.size() - RECENT_TURN_LIMIT);
        List<ChatBackend.Turn> recent = new ArrayList<>(
                cleaned.subList(recentStart, cleaned.size()));

        if (recentStart == 0) return recent;

        List<ChatBackend.Turn> older = cleaned.subList(0, recentStart);
        List<ChatBackend.Turn> extras = findRelevantOlder(older, userMessage);
        if (extras.isEmpty()) return recent;

        List<ChatBackend.Turn> out = new ArrayList<>(extras);
        out.addAll(recent);
        return out;
    }

    private static List<ChatBackend.Turn> findRelevantOlder(List<ChatBackend.Turn> older,
            String userMessage) {
        String q = userMessage == null ? "" : userMessage.toLowerCase(Locale.ROOT);
        List<String> keywords = keywords(q);
        if (keywords.isEmpty()) return Collections.emptyList();

        List<ChatBackend.Turn> hits = new ArrayList<>();
        for (int i = older.size() - 1; i >= 0 && hits.size() < MAX_EXTRA_RELEVANT; i--) {
            ChatBackend.Turn t = older.get(i);
            if (t.text == null) continue;
            String text = t.text.toLowerCase(Locale.ROOT);
            int overlap = 0;
            for (String kw : keywords) {
                if (text.contains(kw)) overlap++;
            }
            if (overlap >= 2 || (overlap >= 1 && keywords.size() == 1)) {
                hits.add(0, t);
            }
        }
        return hits;
    }

    private static List<String> keywords(String query) {
        List<String> out = new ArrayList<>();
        for (String word : query.split("\\s+")) {
            String w = word.trim();
            if (w.length() >= MIN_WORD_LEN) out.add(w);
        }
        return out;
    }
}
