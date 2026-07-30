package com.pegasuscorp.orbe.memory;

import android.content.Context;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Libellés graphe pour l'UI Mémoire et le debug. */
public final class MemoryGraphLabels {

    private MemoryGraphLabels() {}

    public static String entityLinksLine(Context context, MemoryEntry entry) {
        if (context == null || entry == null || entry.entityIds.isEmpty()) return "";
        EntityStore atlas = EntityStore.getInstance(context);
        EntityGraphStore graph = EntityGraphStore.getInstance(context);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < entry.entityIds.size(); i++) {
            if (i > 0) sb.append(" · ");
            String id = entry.entityIds.get(i);
            sb.append(EntityGraphStore.formatEntityName(atlas, id));
            List<EntityEdge> entityEdges = graph.edgesForEntity(id);
            if (!entityEdges.isEmpty() && i == 0) {
                sb.append(" (").append(entityEdges.size()).append(" lien");
                if (entityEdges.size() > 1) sb.append('s');
                sb.append(" atlas)");
            }
        }
        return sb.toString();
    }

    public static String relatedMemoriesLine(MemoryRepository repo, MemoryEntry entry) {
        List<MemoryEntry> related = repo.getLinkedMemories(entry);
        if (related.isEmpty()) return "";
        if (related.size() == 1) {
            return "↔ 1 souvenir lié : " + clip(related.get(0).content, 48);
        }
        return "↔ " + related.size() + " souvenirs liés";
    }

    public static List<String> atlasEdgesLines(Context context) {
        EntityStore atlas = EntityStore.getInstance(context);
        EntityGraphStore graph = EntityGraphStore.getInstance(context);
        List<String> lines = new ArrayList<>();
        for (EntityEdge edge : graph.getAllEdges()) {
            lines.add(EntityGraphStore.formatEdgeLabel(atlas, edge));
        }
        return lines;
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        String t = s.trim();
        if (t.length() <= max) return t;
        return t.substring(0, max - 1) + "…";
    }
}
