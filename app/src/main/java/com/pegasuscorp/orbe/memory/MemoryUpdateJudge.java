package com.pegasuscorp.orbe.memory;

import android.content.Context;
import android.util.Log;

import com.pegasuscorp.orbe.session.PegaseSession;

import java.util.List;
import java.util.Locale;

/**
 * Phase update Mem0-style : top-k embedding → LLM ADD/UPDATE/DELETE/NOOP.
 * Tour background uniquement (consolidation de session).
 */
public final class MemoryUpdateJudge {

    private static final String TAG = "MemoryUpdateJudge";
    static final int TOP_K = 5;
    /** Seuil bas : on veut aussi les contradictions proches, pas seulement les doublons. */
    static final float NEIGHBOR_MIN_SCORE = 0.40f;

    /** Tests : forcer une décision sans LLM. */
    public interface DecisionOverride {
        MemoryUpdateDecision decide(String fact, String category, List<MemoryEntry> neighbors);
    }

    private static volatile boolean enabled = true;
    private static volatile DecisionOverride overrideForTests;

    private MemoryUpdateJudge() {}

    public static void setEnabledForTests(boolean on) {
        enabled = on;
    }

    public static void setOverrideForTests(DecisionOverride override) {
        overrideForTests = override;
    }

    /**
     * Applique le jugement pour un candidat. Retourne true si le fait a été
     * absorbé (ADD/UPDATE/DELETE+ADD/NOOP) — l'appelant ne doit pas re-promouvoir.
     */
    public static boolean judgeAndApply(Context ctx, MemoryRepository repo,
            String fact, String category, double importance, String createdAt) {
        if (ctx == null || repo == null || fact == null) return false;
        String trimmed = fact.trim();
        if (trimmed.isEmpty()) return false;

        if (isExactDuplicate(repo, trimmed)) {
            Log.d(TAG, "NOOP exact: " + trimmed);
            return true;
        }

        List<MemoryEntry> neighbors = repo.findSimilarMemories(trimmed, TOP_K, NEIGHBOR_MIN_SCORE);
        DecisionOverride override = overrideForTests;
        if (override != null && neighbors.isEmpty()) {
            neighbors = new java.util.ArrayList<>();
            for (MemoryEntry e : repo.getAllPermanentMemories()) {
                if (MemoryRepository.isInjectable(e)) neighbors.add(e);
                if (neighbors.size() >= TOP_K) break;
            }
        }
        if (neighbors.isEmpty()) {
            repo.addPermanentMemory(new MemoryEntry(category, trimmed, importance, createdAt));
            Log.d(TAG, "ADD (pas de voisin): " + trimmed);
            return true;
        }

        MemoryUpdateDecision decision = decide(ctx, trimmed, category, neighbors);
        if ("llm_unavailable".equals(decision.reason)
                || "judge_disabled".equals(decision.reason)) {
            return false;
        }
        return apply(repo, trimmed, category, importance, createdAt, neighbors, decision);
    }

    static MemoryUpdateDecision decide(Context ctx, String fact, String category,
            List<MemoryEntry> neighbors) {
        DecisionOverride override = overrideForTests;
        if (override != null) {
            return override.decide(fact, category, neighbors);
        }
        if (!enabled) {
            return MemoryUpdateDecision.noop("judge_disabled");
        }
        String prompt = MemoryUpdateDecision.buildPrompt(fact, category, neighbors);
        try {
            String raw = PegaseSession.get(ctx).completeMemoryUpdateSync(prompt);
            MemoryUpdateDecision d = MemoryUpdateDecision.parse(raw);
            Log.i(TAG, "decision=" + d.op + " id=" + d.targetIndex + " reason=" + d.reason);
            return d;
        } catch (Exception e) {
            Log.w(TAG, "LLM juge indisponible, fallback NOOP si sémantique proche", e);
            return MemoryUpdateDecision.noop("llm_unavailable");
        }
    }

    static boolean apply(MemoryRepository repo, String fact, String category,
            double importance, String createdAt,
            List<MemoryEntry> neighbors, MemoryUpdateDecision decision) {
        if (decision == null) decision = MemoryUpdateDecision.noop("null");
        switch (decision.op) {
            case NOOP:
                Log.d(TAG, "NOOP: " + decision.reason);
                return true;
            case UPDATE: {
                MemoryEntry target = neighborAt(neighbors, decision.targetIndex);
                if (target == null) {
                    repo.addPermanentMemory(new MemoryEntry(category, fact, importance, createdAt));
                    return true;
                }
                String newContent = decision.updatedContent != null
                        ? decision.updatedContent.trim() : fact;
                if (newContent.isEmpty()) newContent = fact;
                repo.updateMemoryContent(target, newContent);
                Log.d(TAG, "UPDATE: " + newContent);
                return true;
            }
            case DELETE: {
                MemoryEntry target = neighborAt(neighbors, decision.targetIndex);
                if (target == null) {
                    repo.addPermanentMemory(new MemoryEntry(category, fact, importance, createdAt));
                    return true;
                }
                MemoryEntry neu = new MemoryEntry(category, fact, importance, createdAt);
                repo.addPermanentMemory(neu);
                repo.invalidateMemory(target, decision.reason.isEmpty()
                        ? ("contredit par : " + fact) : decision.reason, neu.memoryKey());
                Log.d(TAG, "DELETE+ADD: invalidated → " + fact);
                return true;
            }
            case ADD:
            default:
                repo.addPermanentMemory(new MemoryEntry(category, fact, importance, createdAt));
                Log.d(TAG, "ADD: " + fact);
                return true;
        }
    }

    private static MemoryEntry neighborAt(List<MemoryEntry> neighbors, int index) {
        if (neighbors == null || index < 0 || index >= neighbors.size()) return null;
        return neighbors.get(index);
    }

    static boolean isExactDuplicate(MemoryRepository repo, String fact) {
        List<MemoryEntry> existing = repo.getAllPermanentMemories();
        for (MemoryEntry e : existing) {
            if (e == null || e.content == null || e.isInvalid()) continue;
            if (EphemeralMemoryFilter.samePendingIntent(e.content, fact)) return true;
            String lower = fact.toLowerCase(Locale.ROOT);
            String ec = e.content.toLowerCase(Locale.ROOT);
            if (ec.equals(lower) || ec.contains(lower) || lower.contains(ec)) return true;
        }
        return false;
    }
}
