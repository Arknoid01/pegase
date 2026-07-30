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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Graphe d'entités atlas — arêtes typées et pondérées dans {@code entity_edges.json}.
 */
public final class EntityGraphStore {

    private static final double STRENGTHEN_DELTA = 0.05;

    public static final class EntityReach {
        public final Set<String> hop0 = new LinkedHashSet<>();
        public final Set<String> hop1 = new LinkedHashSet<>();
        public final Set<String> hop2 = new LinkedHashSet<>();
        /** Force de chemin maximale [0, 1] depuis une graine jusqu'à l'entité. */
        public final Map<String, Double> strength = new HashMap<>();

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

        public double strengthFor(String entityId) {
            if (entityId == null) return 0;
            return strength.getOrDefault(entityId, 0.0);
        }
    }

    private static final class WeightedNeighbor {
        final String id;
        final double weight;

        WeightedNeighbor(String id, double weight) {
            this.id = id;
            this.weight = weight;
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
        link(fromId, toId, type, EntityEdge.defaultWeight(type));
    }

    public void link(String fromId, String toId, String type, double weight) {
        if (fromId == null || toId == null || fromId.isEmpty() || toId.isEmpty()) return;
        if (fromId.equals(toId)) return;
        EntityEdge candidate = new EntityEdge(fromId, toId, type, weight);
        for (int i = 0; i < edges.size(); i++) {
            EntityEdge existing = edges.get(i);
            if (!existing.undirectedKey().equals(candidate.undirectedKey())) continue;
            double strengthened = Math.min(1.0, existing.weight + STRENGTHEN_DELTA);
            double merged = Math.max(existing.weight, Math.max(weight, strengthened));
            if (merged > existing.weight + 0.0001) {
                edges.set(i, new EntityEdge(existing.fromId, existing.toId, existing.type, merged));
                save();
            }
            return;
        }
        edges.add(candidate);
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

    private List<WeightedNeighbor> weightedNeighbors(String entityId) {
        List<WeightedNeighbor> out = new ArrayList<>();
        if (entityId == null) return out;
        for (EntityEdge e : edges) {
            if (entityId.equals(e.fromId)) {
                out.add(new WeightedNeighbor(e.toId, e.weight));
            } else if (entityId.equals(e.toId)) {
                out.add(new WeightedNeighbor(e.fromId, e.weight));
            }
        }
        return out;
    }

    /**
     * Expansion multi-hop pondérée : force = produit des poids le long du meilleur chemin.
     */
    public EntityReach expand(Collection<String> seeds, int maxHops) {
        EntityReach reach = new EntityReach();
        if (seeds == null || seeds.isEmpty() || maxHops < 0) return reach;

        Map<String, Double> best = new HashMap<>();
        Set<String> frontier = new HashSet<>();
        for (String seed : seeds) {
            if (seed == null || seed.isEmpty()) continue;
            reach.hop0.add(seed);
            best.put(seed, 1.0);
            reach.strength.put(seed, 1.0);
            frontier.add(seed);
        }

        for (int hop = 1; hop <= maxHops && !frontier.isEmpty(); hop++) {
            Set<String> next = new HashSet<>();
            for (String id : frontier) {
                double base = best.getOrDefault(id, 0.0);
                if (base <= 0) continue;
                for (WeightedNeighbor nb : weightedNeighbors(id)) {
                    double pathStrength = base * nb.weight;
                    if (pathStrength <= best.getOrDefault(nb.id, 0.0)) continue;
                    best.put(nb.id, pathStrength);
                    reach.strength.put(nb.id, pathStrength);
                    next.add(nb.id);
                    if (hop == 1) reach.hop1.add(nb.id);
                    else if (hop == 2) reach.hop2.add(nb.id);
                }
            }
            frontier = next;
        }
        return reach;
    }

    public void inferFromMemory(Context context, MemoryEntry entry) {
        if (entry == null || entry.entityIds.size() < 2) return;
        EntityStore atlas = EntityStore.getInstance(context);
        double importanceFactor = 0.7 + 0.3 * Math.min(1.0, Math.max(0.0, entry.importance));
        for (int i = 0; i < entry.entityIds.size(); i++) {
            for (int j = i + 1; j < entry.entityIds.size(); j++) {
                String a = entry.entityIds.get(i);
                String b = entry.entityIds.get(j);
                Entity ea = atlas.findById(a);
                Entity eb = atlas.findById(b);
                String type = inferEdgeType(ea, eb);
                double weight = EntityEdge.defaultWeight(type) * importanceFactor;
                link(a, b, type, weight);
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
        link("project_pegase", "device_nothing_phone", EntityEdge.TYPE_RUNS_ON, 0.95);
        link("project_fableris", "project_pegase", EntityEdge.TYPE_RELATED_TO, 0.70);
        save();
    }

    private void seedDefaultsIfEmpty() {
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

    /** Libellé UI : {@code Pégase ──0.95── Orion}. */
    public static String formatEdgeLabel(EntityStore atlas, EntityEdge edge) {
        if (edge == null) return "";
        Entity from = atlas != null ? atlas.findById(edge.fromId) : null;
        Entity to = atlas != null ? atlas.findById(edge.toId) : null;
        String a = from != null ? from.name : edge.fromId;
        String b = to != null ? to.name : edge.toId;
        return a + " ──" + EntityEdge.formatWeight(edge.weight) + "── " + b;
    }

    public static String formatEntityName(EntityStore atlas, String entityId) {
        if (entityId == null) return "";
        Entity e = atlas != null ? atlas.findById(entityId) : null;
        return e != null ? e.name : entityId.replace('_', ' ');
    }
}
