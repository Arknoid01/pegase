package com.pegasuscorp.orbe.memory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Expansion 1-hop du graphe mémoire (entités atlas + liens entre souvenirs).
 */
public final class MemoryGraph {

    static final double GRAPH_LINK_BOOST = 0.18;

    private MemoryGraph() {}

    /**
     * Ajoute des candidats liés aux entités de la requête ou aux souvenirs déjà classés.
     */
    public static List<MemoryEntry> expandCandidates(List<MemoryEntry> ranked,
            List<MemoryEntry> allPermanent, List<String> seedEntityIds, int maxCandidates) {
        if (allPermanent == null || allPermanent.isEmpty()) return ranked;
        Set<String> seenKeys = new HashSet<>();
        List<MemoryEntry> out = new ArrayList<>();
        if (ranked != null) {
            for (MemoryEntry e : ranked) {
                if (e == null || !MemoryRepository.isInjectable(e)) continue;
                String key = e.memoryKey();
                if (seenKeys.add(key)) out.add(e);
            }
        }

        Set<String> activeEntities = new HashSet<>();
        if (seedEntityIds != null) activeEntities.addAll(seedEntityIds);
        for (MemoryEntry e : out) {
            activeEntities.addAll(e.entityIds);
        }

        if (activeEntities.isEmpty() && out.isEmpty()) return out;

        List<MemoryEntry> graphHits = new ArrayList<>();
        for (MemoryEntry candidate : allPermanent) {
            if (candidate == null || !MemoryRepository.isInjectable(candidate)) continue;
            String key = candidate.memoryKey();
            if (seenKeys.contains(key)) continue;
            if (sharesEntity(candidate, activeEntities) || linkedToRanked(candidate, out)) {
                graphHits.add(candidate);
            }
        }

        graphHits.sort((a, b) -> Double.compare(b.importance, a.importance));
        for (MemoryEntry hit : graphHits) {
            if (out.size() >= maxCandidates) break;
            if (seenKeys.add(hit.memoryKey())) {
                out.add(0, hit);
            }
        }
        while (out.size() > maxCandidates) {
            out.remove(out.size() - 1);
        }
        return out;
    }

    static boolean sharesEntity(MemoryEntry entry, Set<String> entityIds) {
        if (entry == null || entityIds == null || entityIds.isEmpty()) return false;
        for (String id : entry.entityIds) {
            if (entityIds.contains(id)) return true;
        }
        return false;
    }

    private static boolean linkedToRanked(MemoryEntry candidate, List<MemoryEntry> ranked) {
        if (candidate.relatedMemoryKeys.isEmpty() || ranked.isEmpty()) return false;
        for (MemoryEntry r : ranked) {
            if (candidate.relatedMemoryKeys.contains(r.memoryKey())) return true;
            if (r.relatedMemoryKeys.contains(candidate.memoryKey())) return true;
        }
        return false;
    }

    /** Lie deux souvenirs partageant une entité (consolidation / écriture). */
    public static void linkRelated(MemoryEntry a, MemoryEntry b) {
        if (a == null || b == null) return;
        String keyA = a.memoryKey();
        String keyB = b.memoryKey();
        if (keyA.equals(keyB)) return;
        if (!a.relatedMemoryKeys.contains(keyB)) a.relatedMemoryKeys.add(keyB);
        if (!b.relatedMemoryKeys.contains(keyA)) b.relatedMemoryKeys.add(keyA);
    }

    static void linkSharedEntities(MemoryEntry a, MemoryEntry b) {
        if (a == null || b == null) return;
        for (String id : a.entityIds) {
            if (b.entityIds.contains(id)) {
                linkRelated(a, b);
                return;
            }
        }
    }
}
