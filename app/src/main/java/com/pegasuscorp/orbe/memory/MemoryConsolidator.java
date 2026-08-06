package com.pegasuscorp.orbe.memory;

import android.content.Context;
import android.util.Log;

import com.pegasuscorp.orbe.rag.EmbeddingEngine;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Consolidation : promotion session → permanente via juge Mem0-style
 * (ADD / UPDATE / DELETE soft / NOOP), avec repli dédup cosine si LLM down.
 */
public final class MemoryConsolidator {

    private static final String TAG = "MemoryConsolidator";
    /** Au-dessus de ce cosine, un fait est considéré comme doublon (fallback sans LLM). */
    static final float SEMANTIC_DEDUP_THRESHOLD = 0.85f;

    /**
     * Promotion sur thread dédié : le juge Mem0 fait un appel LLM synchrone
     * (latch 30 s) dont la réponse est postée sur le main thread — bloquer le
     * main ici le deadlockait jusqu'au timeout, par fait promu → ANR en fin
     * de discussion. L'embedding MiniLM de findSimilarMemories est aussi
     * trop lourd pour le main.
     */
    private static final ExecutorService CONSOLIDATE_IO =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "pegase-memory-consolidate");
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            });

    private static volatile boolean synchronousForTests;

    private MemoryConsolidator() {}

    /** Tests : exécuter la promotion sur le thread appelant. */
    public static void setSynchronousForTests(boolean on) {
        synchronousForTests = on;
    }

    /**
     * Promouvoit les faits, décisions et sujets en attente d'un résumé de session
     * en souvenirs permanents (juge sémantique + LLM).
     */
    public static void promoteSessionFacts(Context context, SessionSummary summary) {
        promoteSession(context, summary);
    }

    public static void promoteSession(Context context, SessionSummary summary) {
        if (context == null || summary == null) return;
        if (synchronousForTests || android.os.Looper.getMainLooper() == null
                || android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            promoteSessionBlocking(context, summary);
            return;
        }
        CONSOLIDATE_IO.execute(() -> {
            try {
                promoteSessionBlocking(context, summary);
            } catch (Exception e) {
                Log.w(TAG, "Consolidation session échouée", e);
            }
        });
    }

    private static void promoteSessionBlocking(Context context, SessionSummary summary) {
        promoteItems(context, summary.importantFacts, "session", 0.72, false);
        promoteItems(context, summary.decisions, "decision", 0.68, false);
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
            try {
                if (MemoryUpdateJudge.judgeAndApply(
                        context, repo, trimmed, category, importance, today)) {
                    continue;
                }
            } catch (Exception e) {
                Log.w(TAG, "Juge mémoire échoué, fallback dédup", e);
            }
            if (isDuplicate(repo, context, trimmed)) {
                Log.d(TAG, "Élément ignoré (doublon fallback): " + trimmed);
                continue;
            }
            repo.addPermanentMemory(new MemoryEntry(category, trimmed, importance, today));
            Log.d(TAG, "Élément promu [" + category + "]: " + trimmed);
        }
    }

    static boolean isDuplicate(MemoryRepository repo, Context context, String fact) {
        List<MemoryEntry> existing = repo.getAllPermanentMemories();
        for (MemoryEntry e : existing) {
            if (e == null || e.content == null || e.isInvalid()) continue;
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
                if (e == null || e.content == null || e.content.isEmpty() || e.isInvalid()) {
                    continue;
                }
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
