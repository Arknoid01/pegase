package com.pegasuscorp.orbe.memory;

import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;

/**
 * Scoring unifié pour la récupération de souvenirs (mots-clés et sémantique composite).
 */
public final class MemoryScorer {

    private MemoryScorer() {}

    /** Score mots-clés : pertinence requête + entités + récence + importance. */
    public static double keywordScore(MemoryEntry entry, String query, List<String> entityTerms) {
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT);
        double relevance = queryRelevance(entry, q);
        double entityBoost = entityRelevance(entry, entityTerms);
        double recency = recencyBoost(entry.createdAt);
        double importance = entry.importance * 0.15;
        return relevance * 0.4 + entityBoost * 0.4 + recency * 0.05 + importance;
    }

    public static double compositeSemantic(MemoryEntry entry, float cosine, List<String> entityTerms) {
        return compositeSemantic(entry, cosine, entityTerms, null);
    }

    /**
     * Re-rank sémantique : cosine MiniLM + entités + graphe + récence + importance.
     */
    public static double compositeSemantic(MemoryEntry entry, float cosine, List<String> entityTerms,
            List<String> seedEntityIds) {
        double entityBoost = entityRelevance(entry, entityTerms);
        double graphBoost = graphEntityBoost(entry, seedEntityIds);
        double recency = recencyBoost(entry.createdAt);
        double importance = Math.min(1.0, entry.importance) * 0.10;
        double c = Math.max(0f, Math.min(1f, cosine));
        return c * 0.50 + entityBoost * 0.22 + graphBoost * 0.13 + recency * 0.10 + importance;
    }

    static double graphEntityBoost(MemoryEntry entry, List<String> seedEntityIds) {
        if (seedEntityIds == null || seedEntityIds.isEmpty() || entry.entityIds.isEmpty()) {
            return 0;
        }
        for (String id : entry.entityIds) {
            if (seedEntityIds.contains(id)) return MemoryGraph.GRAPH_LINK_BOOST;
        }
        return 0;
    }

    static double queryRelevance(MemoryEntry entry, String queryLower) {
        if (queryLower.isEmpty() || entry.content == null) return 0.1;
        String c = entry.content.toLowerCase(Locale.ROOT);
        double score = 0.1;
        for (String word : queryLower.split("\\s+")) {
            if (word.length() > 3 && c.contains(word)) score += 0.22;
        }
        return Math.min(1.0, score);
    }

    static double entityRelevance(MemoryEntry entry, List<String> entityTerms) {
        if (entityTerms == null || entityTerms.isEmpty() || entry.content == null) return 0;
        String c = entry.content.toLowerCase(Locale.ROOT);
        String cat = entry.category != null ? entry.category.toLowerCase(Locale.ROOT) : "";
        double best = 0;
        for (String term : entityTerms) {
            if (term == null || term.isEmpty()) continue;
            String t = term.toLowerCase(Locale.ROOT);
            if (c.contains(t)) best = Math.max(best, 0.95);
            else if (cat.contains(t)) best = Math.max(best, 0.75);
        }
        return best;
    }

    static double recencyBoost(String createdAt) {
        if (createdAt == null || createdAt.isEmpty()) return 0;
        try {
            long created = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).parse(createdAt).getTime();
            long days = (System.currentTimeMillis() - created) / (24L * 60 * 60 * 1000);
            if (days <= 7) return 0.2;
            if (days <= 30) return 0.1;
        } catch (Exception ignored) {}
        return 0;
    }
}
