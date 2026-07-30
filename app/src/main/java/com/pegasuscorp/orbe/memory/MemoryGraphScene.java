package com.pegasuscorp.orbe.memory;

import android.content.Context;

import java.util.ArrayList;
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

        for (EntityEdge edge : entityGraph.getAllEdges()) {
            ensureEntityNode(nodesById, atlas, edge.fromId);
            ensureEntityNode(nodesById, atlas, edge.toId);
            addEdge(edges, edgeKeys, edge.fromId, edge.toId, edge.weight, edge.frozen, true);
        }

        List<MemoryEntry> memories = repo.getAllPermanentMemories();
        for (MemoryEntry entry : memories) {
            if (entry == null || !MemoryRepository.isInjectable(entry)) continue;
            String memId = memoryNodeId(entry);
            nodesById.put(memId, new Node(
                    memId, clip(entry.content, 18), NodeKind.MEMORY, entry.category,
                    entry.effectiveImportance()));
        }

        for (MemoryEntry entry : memories) {
            if (entry == null || !MemoryRepository.isInjectable(entry)) continue;
            String memId = memoryNodeId(entry);
            for (String entityId : entry.entityIds) {
                ensureEntityNode(nodesById, atlas, entityId);
                addEdge(edges, edgeKeys, memId, entityId,
                        Math.max(0.35, entry.effectiveImportance()), entry.frozen, false);
            }
            for (String relatedKey : entry.relatedMemoryKeys) {
                String otherId = "mem:" + relatedKey;
                if (nodesById.containsKey(otherId)) {
                    addEdge(edges, edgeKeys, memId, otherId, 0.55, false, false);
                }
            }
        }

        List<Node> entityNodes = new ArrayList<>();
        List<Node> memoryNodes = new ArrayList<>();
        for (Node node : nodesById.values()) {
            if (node.kind == NodeKind.ENTITY) entityNodes.add(node);
            else memoryNodes.add(node);
        }

        layoutSphere(entityNodes, 1.35f);
        layoutMemories(memoryNodes, nodesById, edges);

        return new Scene(new ArrayList<>(nodesById.values()), edges);
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

    private static void addEdge(List<Edge> edges, Set<String> edgeKeys,
            String a, String b, double weight, boolean frozen, boolean entityLink) {
        if (a == null || b == null || a.equals(b)) return;
        String key = a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a;
        if (!edgeKeys.add(key)) return;
        edges.add(new Edge(a, b, weight, frozen, entityLink));
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
            node.y = radius * y * 0.85f;
            node.z = radius * (float) Math.sin(theta) * ring;
        }
    }

    static void layoutMemories(List<Node> memoryNodes, Map<String, Node> nodesById,
            List<Edge> edges) {
        Map<String, List<String>> anchors = new LinkedHashMap<>();
        for (Edge edge : edges) {
            if (edge.fromId.startsWith("mem:") && !edge.toId.startsWith("mem:")) {
                anchors.computeIfAbsent(edge.fromId, k -> new ArrayList<>()).add(edge.toId);
            } else if (edge.toId.startsWith("mem:") && !edge.fromId.startsWith("mem:")) {
                anchors.computeIfAbsent(edge.toId, k -> new ArrayList<>()).add(edge.fromId);
            }
        }

        int idx = 0;
        for (Node mem : memoryNodes) {
            List<String> linked = anchors.get(mem.id);
            float cx = 0, cy = 0, cz = 0;
            int count = 0;
            if (linked != null) {
                for (String anchorId : linked) {
                    Node anchor = nodesById.get(anchorId);
                    if (anchor == null) continue;
                    cx += anchor.x;
                    cy += anchor.y;
                    cz += anchor.z;
                    count++;
                }
            }
            if (count > 0) {
                cx /= count;
                cy /= count;
                cz /= count;
            }
            float orbit = 0.42f + (idx % 5) * 0.09f;
            float angle = idx * 2.15f;
            mem.x = cx + orbit * (float) Math.cos(angle);
            mem.y = cy + 0.15f + orbit * 0.75f * (float) Math.sin(angle * 0.9f);
            mem.z = cz + orbit * (float) Math.sin(angle);
            idx++;
        }
    }

    private static String clip(String text, int max) {
        if (text == null) return "";
        String t = text.trim();
        if (t.length() <= max) return t;
        return t.substring(0, max - 1) + "…";
    }
}
