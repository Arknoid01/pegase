package com.pegasuscorp.orbe.memory;

import android.content.Context;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Données 3D pour l'aperçu graphe mémoire (entités + souvenirs + liens). */
public final class MemoryGraphScene {

    public enum NodeKind {
        ENTITY, MEMORY
    }

    /** Plafond d'étoiles affichées — lisibilité + perf. */
    static final int MAX_MEMORY_NODES = 36;
    static final int MAX_ENTITY_EDGES = 24;

    public static final class Node {
        public final String id;
        public final String label;
        public final NodeKind kind;
        public final String entityType;
        /** Importance souvenir ou poids moyen entité [0, 1]. */
        public final double vitality;
        public float x;
        public float y;
        public float z;

        Node(String id, String label, NodeKind kind, String entityType, double vitality) {
            this.id = id;
            this.label = label;
            this.kind = kind;
            this.entityType = entityType != null ? entityType : "";
            this.vitality = vitality;
        }
    }

    public static final class Edge {
        public final String fromId;
        public final String toId;
        public final double weight;
        public final boolean frozen;
        public final boolean entityLink;

        Edge(String fromId, String toId, double weight, boolean frozen, boolean entityLink) {
            this.fromId = fromId;
            this.toId = toId;
            this.weight = weight;
            this.frozen = frozen;
            this.entityLink = entityLink;
        }
    }

    public static final class Scene {
        public final List<Node> nodes;
        public final List<Edge> edges;

        public Scene(List<Node> nodes, List<Edge> edges) {
            this.nodes = nodes;
            this.edges = edges;
        }

        public boolean isEmpty() {
            return nodes.isEmpty();
        }
    }

    private MemoryGraphScene() {}

    public static Scene build(Context context) {
        if (context == null) return new Scene(new ArrayList<>(), new ArrayList<>());
        EntityStore atlas = EntityStore.getInstance(context);
        EntityGraphStore entityGraph = EntityGraphStore.getInstance(context);
        MemoryRepository repo = MemoryRepository.getInstance(context);

        Map<String, Node> nodesById = new LinkedHashMap<>();
        List<Edge> edges = new ArrayList<>();
        Set<String> edgeKeys = new HashSet<>();

        List<EntityEdge> entityEdges = new ArrayList<>(entityGraph.getAllEdges());
        entityEdges.sort((a, b) -> {
            int frozen = Boolean.compare(b.frozen, a.frozen);
            if (frozen != 0) return frozen;
            return Double.compare(b.weight, a.weight);
        });
        int entityEdgeCount = 0;
        for (EntityEdge edge : entityEdges) {
            if (!edge.frozen && edge.weight < 0.35) continue;
            if (entityEdgeCount >= MAX_ENTITY_EDGES) break;
            ensureEntityNode(nodesById, atlas, edge.fromId);
            ensureEntityNode(nodesById, atlas, edge.toId);
            if (addEdge(edges, edgeKeys, edge.fromId, edge.toId, edge.weight, edge.frozen, true)) {
                entityEdgeCount++;
            }
        }

        List<MemoryEntry> memories = new ArrayList<>();
        for (MemoryEntry entry : repo.getAllPermanentMemories()) {
            if (entry == null || !MemoryRepository.isInjectable(entry)) continue;
            memories.add(entry);
        }
        memories.sort((a, b) -> Double.compare(b.effectiveImportance(), a.effectiveImportance()));
        if (memories.size() > MAX_MEMORY_NODES) {
            memories = new ArrayList<>(memories.subList(0, MAX_MEMORY_NODES));
        }

        for (MemoryEntry entry : memories) {
            String memId = memoryNodeId(entry);
            nodesById.put(memId, new Node(
                    memId, clip(entry.content, 18), NodeKind.MEMORY, entry.category,
                    entry.effectiveImportance()));
        }

        for (MemoryEntry entry : memories) {
            String memId = memoryNodeId(entry);
            // Un seul lien primaire par souvenir — pas de spaghetti mem↔mem.
            String primary = primaryEntityId(entry);
            if (primary == null) continue;
            ensureEntityNode(nodesById, atlas, primary);
            addEdge(edges, edgeKeys, memId, primary,
                    Math.max(0.35, entry.effectiveImportance()), entry.frozen, false);
        }

        List<Node> entityNodes = new ArrayList<>();
        List<Node> memoryNodes = new ArrayList<>();
        for (Node node : nodesById.values()) {
            if (node.kind == NodeKind.ENTITY) entityNodes.add(node);
            else memoryNodes.add(node);
        }

        layoutEntitiesBySector(entityNodes);
        layoutMemories(memoryNodes, nodesById, edges);

        return new Scene(new ArrayList<>(nodesById.values()), edges);
    }

    /** Entité la plus « ancre » d'un souvenir (première id non vide). */
    private static String primaryEntityId(MemoryEntry entry) {
        if (entry.entityIds == null) return null;
        for (String id : entry.entityIds) {
            if (id != null && !id.isEmpty()) return id;
        }
        return null;
    }

    static String memoryNodeId(MemoryEntry entry) {
        return "mem:" + entry.memoryKey();
    }

    private static void ensureEntityNode(Map<String, Node> nodesById, EntityStore atlas,
            String entityId) {
        if (entityId == null || entityId.isEmpty() || nodesById.containsKey(entityId)) return;
        Entity entity = atlas.findById(entityId);
        String label = entity != null ? entity.name : entityId.replace('_', ' ');
        String type = entity != null ? entity.type : "";
        nodesById.put(entityId, new Node(entityId, label, NodeKind.ENTITY, type, 0.85));
    }

    private static boolean addEdge(List<Edge> edges, Set<String> edgeKeys,
            String a, String b, double weight, boolean frozen, boolean entityLink) {
        if (a == null || b == null || a.equals(b)) return false;
        String key = a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a;
        if (!edgeKeys.add(key)) return false;
        edges.add(new Edge(a, b, weight, frozen, entityLink));
        return true;
    }

    /**
     * Entités sur un anneau horizontal, regroupées par type (secteurs).
     * Lecture : continents distincts plutôt qu'une sphère fouillis.
     */
    static void layoutEntitiesBySector(List<Node> entities) {
        if (entities.isEmpty()) return;
        if (entities.size() == 1) {
            entities.get(0).x = 1.4f;
            entities.get(0).y = 0f;
            entities.get(0).z = 0f;
            return;
        }

        Map<String, List<Node>> sectors = new LinkedHashMap<>();
        sectors.put(Entity.TYPE_PERSON, new ArrayList<>());
        sectors.put(Entity.TYPE_PROJECT, new ArrayList<>());
        sectors.put(Entity.TYPE_DEVICE, new ArrayList<>());
        sectors.put("other", new ArrayList<>());
        for (Node node : entities) {
            String key = sectorKey(node.entityType);
            sectors.get(key).add(node);
        }

        String[] order = {
                Entity.TYPE_PERSON, Entity.TYPE_PROJECT, Entity.TYPE_DEVICE, "other"
        };
        float ring = 1.7f + Math.min(0.9f, entities.size() * 0.04f);
        float sectorSpan = (float) (Math.PI * 2.0 / order.length);
        float gap = sectorSpan * 0.18f;

        for (int s = 0; s < order.length; s++) {
            List<Node> group = sectors.get(order[s]);
            if (group.isEmpty()) continue;
            float start = s * sectorSpan + gap * 0.5f;
            float usable = sectorSpan - gap;
            int n = group.size();
            for (int i = 0; i < n; i++) {
                float t = n == 1 ? 0.5f : i / (float) (n - 1);
                float angle = start + usable * t;
                Node node = group.get(i);
                node.x = ring * (float) Math.cos(angle);
                node.y = ((i % 3) - 1) * 0.18f;
                node.z = ring * (float) Math.sin(angle);
            }
        }
    }

    private static String sectorKey(String entityType) {
        if (Entity.TYPE_PERSON.equals(entityType)) return Entity.TYPE_PERSON;
        if (Entity.TYPE_PROJECT.equals(entityType)) return Entity.TYPE_PROJECT;
        if (Entity.TYPE_DEVICE.equals(entityType)) return Entity.TYPE_DEVICE;
        return "other";
    }

    static void layoutSphere(List<Node> nodes, float radius) {
        if (nodes.isEmpty()) return;
        if (nodes.size() == 1) {
            nodes.get(0).x = 0;
            nodes.get(0).y = 0;
            nodes.get(0).z = 0;
            return;
        }
        float golden = (float) (Math.PI * (3.0 - Math.sqrt(5.0)));
        for (int i = 0; i < nodes.size(); i++) {
            float t = i / (float) (nodes.size() - 1);
            float y = 1f - 2f * t;
            float ring = (float) Math.sqrt(Math.max(0f, 1f - y * y));
            float theta = golden * i;
            Node node = nodes.get(i);
            node.x = radius * (float) Math.cos(theta) * ring;
            node.y = radius * y * 0.55f;
            node.z = radius * (float) Math.sin(theta) * ring;
        }
    }

    /**
     * Souvenirs en « systèmes solaires » plats autour de leur entité primaire.
     */
    static void layoutMemories(List<Node> memoryNodes, Map<String, Node> nodesById,
            List<Edge> edges) {
        Map<String, List<String>> anchors = new LinkedHashMap<>();
        for (Edge edge : edges) {
            if (edge.entityLink) continue;
            if (edge.fromId.startsWith("mem:") && !edge.toId.startsWith("mem:")) {
                anchors.computeIfAbsent(edge.fromId, k -> new ArrayList<>()).add(edge.toId);
            } else if (edge.toId.startsWith("mem:") && !edge.fromId.startsWith("mem:")) {
                anchors.computeIfAbsent(edge.toId, k -> new ArrayList<>()).add(edge.fromId);
            }
        }

        Map<String, List<Node>> byPrimary = new LinkedHashMap<>();
        List<Node> orphans = new ArrayList<>();
        for (Node mem : memoryNodes) {
            List<String> linked = anchors.get(mem.id);
            String primary = null;
            if (linked != null) {
                for (String id : linked) {
                    if (nodesById.containsKey(id)) {
                        primary = id;
                        break;
                    }
                }
            }
            if (primary == null) orphans.add(mem);
            else byPrimary.computeIfAbsent(primary, k -> new ArrayList<>()).add(mem);
        }

        for (Map.Entry<String, List<Node>> cluster : byPrimary.entrySet()) {
            Node anchor = nodesById.get(cluster.getKey());
            if (anchor == null) continue;
            List<Node> siblings = cluster.getValue();
            int n = siblings.size();
            float orbit = 0.48f + Math.min(0.55f, n * 0.07f);
            for (int i = 0; i < n; i++) {
                Node mem = siblings.get(i);
                // Anneaux concentriques (5 par couronne) — système solaire lisible.
                int ringIndex = i / 5;
                int slot = i % 5;
                int slots = Math.min(5, n - ringIndex * 5);
                float angle = (float) (slot * (2.0 * Math.PI / Math.max(1, slots))
                        + hashAngle(mem.id) + ringIndex * 0.35);
                float ring = orbit + ringIndex * 0.32f;
                mem.x = anchor.x + ring * (float) Math.cos(angle);
                mem.y = anchor.y + ((i % 2) * 2 - 1) * 0.06f;
                mem.z = anchor.z + ring * (float) Math.sin(angle);
            }
        }

        if (!orphans.isEmpty()) {
            // Orphelins : petit arc extérieur, pas une coquille pleine.
            float outer = 2.6f;
            int n = orphans.size();
            float start = (float) (Math.PI * 1.15);
            float span = (float) (Math.PI * 0.7);
            for (int i = 0; i < n; i++) {
                float t = n == 1 ? 0.5f : i / (float) (n - 1);
                float angle = start + span * t;
                Node mem = orphans.get(i);
                mem.x = outer * (float) Math.cos(angle);
                mem.y = ((i % 3) - 1) * 0.2f;
                mem.z = outer * (float) Math.sin(angle);
            }
        }
    }

    private static float hashAngle(String id) {
        if (id == null) return 0f;
        int h = id.hashCode();
        return (h & 0xffff) / 65535f * 0.35f;
    }

    private static String clip(String text, int max) {
        if (text == null) return "";
        String t = text.trim();
        if (t.length() <= max) return t;
        return t.substring(0, max - 1) + "…";
    }
}
