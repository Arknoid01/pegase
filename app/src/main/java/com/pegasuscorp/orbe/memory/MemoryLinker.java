package com.pegasuscorp.orbe.memory;

import android.content.Context;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Auto-liaison souvenirs ↔ entités atlas à l'écriture.
 */
public final class MemoryLinker {

    private static final int MAX_ENTITY_LINKS = 3;

    private MemoryLinker() {}

    /** Enrichit {@code entry.entityIds} depuis le contenu et la catégorie. */
    public static void autoLink(Context context, MemoryEntry entry) {
        if (context == null || entry == null || entry.content == null) return;
        Set<String> ids = new LinkedHashSet<>(entry.entityIds);

        EntityResolver.Resolution resolution = EntityResolver.resolve(context, entry.content);
        for (EntityResolver.EntityMatch match : resolution.forInjection(MAX_ENTITY_LINKS)) {
            if (match.entity != null && match.entity.id != null && !match.entity.id.isEmpty()) {
                ids.add(match.entity.id);
            }
        }

        entry.entityIds.clear();
        int added = 0;
        for (String id : ids) {
            entry.entityIds.add(id);
            if (++added >= MAX_ENTITY_LINKS) break;
        }
    }

    /** IDs d'entités injectées depuis une résolution de requête. */
    public static List<String> seedEntityIds(EntityResolver.Resolution resolution, int max) {
        List<String> out = new ArrayList<>();
        if (resolution == null) return out;
        for (EntityResolver.EntityMatch match : resolution.forInjection(max)) {
            if (match.entity == null || match.entity.id.isEmpty()) continue;
            if (!out.contains(match.entity.id)) out.add(match.entity.id);
        }
        return out;
    }
}
