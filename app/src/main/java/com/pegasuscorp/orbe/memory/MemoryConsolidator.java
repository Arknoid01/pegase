package com.pegasuscorp.orbe.memory;

import android.content.Context;
import android.util.Log;

import com.pegasuscorp.orbe.rag.EmbeddingEngine;
import com.pegasuscorp.orbe.rag.VectorStore;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Consolidation légère : promotion des éléments de session vers la mémoire permanente,
 * avec déduplication textuelle et sémantique.
 */
public final class MemoryConsolidator {

    private static final String TAG = "MemoryConsolidator";
    /** Au-dessus de ce cosine, un fait est considéré comme doublon d'un souvenir existant. */
    static final float SEMANTIC_DEDUP_THRESHOLD = 0.85f;

    private MemoryConsolidator() {}

    /**
     * Promouvoit les faits, décisions et sujets en attente d'un résumé de session
     * en souvenirs permanents s'ils ne dupliquent pas un souvenir existant.
     */
    public static void promoteSessionFacts(Context context, SessionSummary summary) {
        promoteSession(context, summary);
    }

    public static void promoteSession(Context context, SessionSummary summary) {
        if (context == null || summary == null) return;
        // Faits / décisions : défense secondaire (bruit UI / intention de clic).
        promoteItems(context, summary.importantFacts, "session", 0.72, false);
        promoteItems(context, summary.decisions, "decision", 0.68, false);
        // Pending : liste blanche uniquement (rappel humain reporté) — jamais
        // d'intention de clic / négociation UI / « veut cliquer sur … ».
        promoteItems(context, summary.pendingTopics, "pending", 0.65, true);
    }

    private static void promoteItems(Context context, List<String> items, String category,
            double importance, boolean pendingWhitelist) {
        if (items == null || items.isEmpty()) return;
        MemoryRepository repo = MemoryRepository.getInstance(context);
        String today = today();
        for (String item : items) {
            if (item == null) continue;
            String trimmed = item.trim();
            if (trimmed.isEmpty()) continue;
            if (pendingWhitelist) {
                if (!EphemeralMemoryFilter.isDurablePending(trimmed)) {
                    Log.d(TAG, "Pending ignoré (pas whitelist durable): " + trimmed);
                    continue;
                }
            } else if (!EphemeralMemoryFilter.isDurableSessionItem(trimmed)) {
                Log.d(TAG, "Élément ignoré (éphémère): " + trimmed);
                continue;
            }
            if (isDuplicate(repo, context, trimmed)) {
                Log.d(TAG, "Élément ignoré (doublon): " + trimmed);
                continue;
            }
            repo.addPermanentMemory(new MemoryEntry(category, trimmed, importance, today));
            Log.d(TAG, "Élément promu [" + category + "]: " + trimmed);
        }
    }

    static boolean isDuplicate(MemoryRepository repo, Context context, String fact) {
        List<MemoryEntry> existing = repo.getAllPermanentMemories();
        for (MemoryEntry e : existing) {
            if (e.content == null) continue;
            if (EphemeralMemoryFilter.samePendingIntent(e.content, fact)) return true;
            String lower = fact.toLowerCase(Locale.ROOT);
            String ec = e.content.toLowerCase(Locale.ROOT);
            if (ec.contains(lower) || lower.contains(ec)) return true;
        }
        return isSemanticDuplicate(context, existing, fact);
    }

    private static boolean isSemanticDuplicate(Context context, List<MemoryEntry> existing,
            String fact) {
        if (existing.isEmpty()) return false;
        try {
            float[] factVec = EmbeddingEngine.get(context).embed(fact);
            for (MemoryEntry e : existing) {
                if (e.content == null || e.content.isEmpty()) continue;
                float[] memVec = EmbeddingEngine.get(context).embed(e.content);
                float sim = cosine(factVec, memVec);
                if (sim >= SEMANTIC_DEDUP_THRESHOLD) return true;
            }
        } catch (Exception e) {
            Log.w(TAG, "Déduplication sémantique indisponible", e);
        }
        return false;
    }

    private static float cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) return 0f;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) return 0f;
        return (float) (dot / (Math.sqrt(na) * Math.sqrt(nb)));
    }

    private static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());
    }
}
