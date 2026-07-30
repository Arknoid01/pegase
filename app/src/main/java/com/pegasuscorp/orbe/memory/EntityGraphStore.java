package com.pegasuscorp.orbe.memory;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Graphe d'entités atlas — arêtes typées persistées dans {@code entity_edges.json}.
 */
public final class EntityGraphStore {

    public static final class EntityReach {
        public final Set<String> hop0 = new LinkedHashSet<>();
        public final Set<String> hop1 = new LinkedHashSet<>();
        public final Set<String> hop2 = new LinkedHashSet<>();

        public Set<String> allWithin(int maxHops) {
            Set<String> out = new LinkedHashSet<>(hop0);
            if (maxHops >= 1) out.addAll(hop1);
            if (maxHops >= 2) out.addAll(hop2);
            return out;
        }

        public int hopDistance(String entityId) {
            if (entityId == null) return -1;
            if (hop0.contains(entityId)) return 0;
            if (hop1.contains(entityId)) return 1;
            if (hop2.contains(entityId)) return 2;
            return -1;
        }
    }

    private static EntityGraphStore instance;

    private final File edgesFile;
    private final List<EntityEdge> edges = new ArrayList<>();

    private EntityGraphStore(Context context) {
        File memoryDir = new File(context.getApplicationContext().getFilesDir(), "memory");
        if (!memoryDir.exists()) memoryDir.mkdirs();
        edgesFile = new File(memoryDir, "entity_edges.json");
        load();
        seedDefaultsIfEmpty();
    }

    public static synchronized EntityGraphStore getInstance(Context context) {
        if (instance == null) instance = new EntityGraphStore(context);
        return instance;
    }

    public static synchronized void resetInstanceForTests() {
        instance = null;
    }

    public List<EntityEdge> getAllEdges() {
        return Collections.unmodifiableList(edges);
    }

    public List<EntityEdge> edgesForEntity(String entityId) {
        if (entityId == null || entityId.isEmpty()) return Collections.emptyList();
        List<EntityEdge> out = new ArrayList<>();
        for (EntityEdge e : edges) {
            if (entityId.equals(e.fromId) || entityId.equals(e.toId)) out.add(e);
        }
        return out;
    }

    public void link(String fromId, String toId, String type) {
        if (fromId == null || toId == null || fromId.isEmpty() || toId.isEmpty()) return;
        if (fromId.equals(toId)) return;
        EntityEdge edge = new EntityEdge(fromId, toId, type);
        for (EntityEdge existing : edges) {
            if (existing.undirectedKey().equals(edge.undirectedKey())) return;
        }
        edges.add(edge);
        save();
    }

    /** Voisins directs (non orienté). */
    public Set<String> neighbors(String entityId) {
        Set<String> out = new LinkedHashSet<>();
        if (entityId == null) return out;
        for (EntityEdge e : edges) {
            if (entityId.equals(e.fromId)) out.add(e.toId);
            else if (entityId.equals(e.toId)) out.add(e.fromId);
        }
        return out;
    }

    /** Expansion multi-hop depuis les graines (0 = graine, 1 = voisin, 2 = voisin du voisin). */
    public EntityReach expand(Collection<String> seeds, int maxHops) {
        EntityReach reach = new EntityReach();
        if (seeds == null || seeds.isEmpty() || maxHops < 0) return reach;
        Set<String> visited = new HashSet<>();
        List<String> frontier = new ArrayList<>();
        for (String seed : seeds) {
            if (seed == null || seed.isEmpty()) continue;
            if (visited.add(seed)) {
                reach.hop0.add(seed);
                frontier.add(seed);
            }
        }
        for (int hop = 1; hop <= maxHops && !frontier.isEmpty(); hop++) {
            List<String> next = new ArrayList<>();
            for (String id : frontier) {
                for (String neighbor : neighbors(id)) {
                    if (!visited.add(neighbor)) continue;
                    next.add(neighbor);
                    if (hop == 1) reach.hop1.add(neighbor);
                    else if (hop == 2) reach.hop2.add(neighbor);
                }
            }
            frontier = next;
        }
        return reach;
    }

    /**
     * Infère des arêtes depuis un souvenir multi-entités.
     * Projet + appareil → {@code runs_on} ; sinon {@code related_to}.
     */
    public void inferFromMemory(Context context, MemoryEntry entry) {
        if (entry == null || entry.entityIds.size() < 2) return;
        EntityStore atlas = EntityStore.getInstance(context);
        for (int i = 0; i < entry.entityIds.size(); i++) {
            for (int j = i + 1; j < entry.entityIds.size(); j++) {
                String a = entry.entityIds.get(i);
                String b = entry.entityIds.get(j);
                Entity ea = atlas.findById(a);
                Entity eb = atlas.findById(b);
                String type = inferEdgeType(ea, eb);
                link(a, b, type);
            }
        }
    }

    static String inferEdgeType(Entity a, Entity b) {
        if (a == null || b == null) return EntityEdge.TYPE_RELATED_TO;
        if (Entity.TYPE_PROJECT.equals(a.type) && Entity.TYPE_DEVICE.equals(b.type)) {
            return EntityEdge.TYPE_RUNS_ON;
        }
        if (Entity.TYPE_PROJECT.equals(b.type) && Entity.TYPE_DEVICE.equals(a.type)) {
            return EntityEdge.TYPE_RUNS_ON;
        }
        if (Entity.TYPE_PERSON.equals(a.type) && Entity.TYPE_PROJECT.equals(b.type)) {
            return EntityEdge.TYPE_WORKS_ON;
        }
        if (Entity.TYPE_PERSON.equals(b.type) && Entity.TYPE_PROJECT.equals(a.type)) {
            return EntityEdge.TYPE_WORKS_ON;
        }
        if (Entity.TYPE_PREFERENCE.equals(a.type) || Entity.TYPE_PREFERENCE.equals(b.type)) {
            return EntityEdge.TYPE_PREFERS;
        }
        return EntityEdge.TYPE_RELATED_TO;
    }

    private void seedDefaultsIfEmpty() {
        if (!edges.isEmpty()) return;
        link("project_pegase", "device_nothing_phone", EntityEdge.TYPE_RUNS_ON);
        link("project_fableris", "project_pegase", EntityEdge.TYPE_RELATED_TO);
        save();
    }

    private void load() {
        edges.clear();
        if (!edgesFile.exists()) return;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                new FileInputStream(edgesFile), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            JSONArray arr = new JSONArray(sb.toString());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) edges.add(EntityEdge.fromJson(o));
            }
        } catch (Exception ignored) {}
    }

    private void save() {
        JSONArray arr = new JSONArray();
        for (EntityEdge e : edges) {
            try {
                arr.put(e.toJson());
            } catch (Exception ignored) {}
        }
        try (FileOutputStream out = new FileOutputStream(edgesFile)) {
            out.write(arr.toString(2).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
    }

    /** Libellé court pour l'UI Mémoire. */
    public static String formatEdgeLabel(EntityStore atlas, EntityEdge edge) {
        if (edge == null) return "";
        Entity from = atlas != null ? atlas.findById(edge.fromId) : null;
        Entity to = atlas != null ? atlas.findById(edge.toId) : null;
        String a = from != null ? from.name : edge.fromId;
        String b = to != null ? to.name : edge.toId;
        return a + " " + EntityEdge.labelFr(edge.type) + " " + b;
    }

    public static String formatEntityName(EntityStore atlas, String entityId) {
        if (entityId == null) return "";
        Entity e = atlas != null ? atlas.findById(entityId) : null;
        return e != null ? e.name : entityId.replace('_', ' ');
    }
}
