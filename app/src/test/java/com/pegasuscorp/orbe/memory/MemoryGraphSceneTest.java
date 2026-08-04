package com.pegasuscorp.orbe.memory;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

public class MemoryGraphSceneTest {

    @Test
    public void layoutSphere_singleNodeAtOrigin() {
        MemoryGraphScene.Node node = new MemoryGraphScene.Node(
                "a", "A", MemoryGraphScene.NodeKind.ENTITY, "project", 0.9);
        MemoryGraphScene.layoutSphere(Collections.singletonList(node), 1f);
        assertEquals(0f, node.x, 0.001);
        assertEquals(0f, node.y, 0.001);
        assertEquals(0f, node.z, 0.001);
    }

    @Test
    public void layoutSphere_distributesMultipleNodes() {
        MemoryGraphScene.Node a = new MemoryGraphScene.Node(
                "a", "A", MemoryGraphScene.NodeKind.ENTITY, "project", 0.9);
        MemoryGraphScene.Node b = new MemoryGraphScene.Node(
                "b", "B", MemoryGraphScene.NodeKind.ENTITY, "person", 0.8);
        MemoryGraphScene.layoutSphere(Arrays.asList(a, b), 1f);
        float dist = (float) Math.sqrt(
                (a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y) + (a.z - b.z) * (a.z - b.z));
        assertTrue(dist > 0.5f);
    }

    @Test
    public void layoutEntitiesBySector_groupsTypesApart() {
        MemoryGraphScene.Node person = new MemoryGraphScene.Node(
                "p", "Alice", MemoryGraphScene.NodeKind.ENTITY, Entity.TYPE_PERSON, 0.9);
        MemoryGraphScene.Node project = new MemoryGraphScene.Node(
                "j", "Orion", MemoryGraphScene.NodeKind.ENTITY, Entity.TYPE_PROJECT, 0.9);
        MemoryGraphScene.layoutEntitiesBySector(Arrays.asList(person, project));
        float d = dist(person, project);
        assertTrue(d > 1.5f);
        // Anneau horizontal : peu d'élévation
        assertTrue(Math.abs(person.y) < 0.4f);
        assertTrue(Math.abs(project.y) < 0.4f);
    }

    @Test
    public void layoutMemories_orbitsNearAnchors() {
        MemoryGraphScene.Node entity = new MemoryGraphScene.Node(
                "entity", "Pégase", MemoryGraphScene.NodeKind.ENTITY, "project", 0.9);
        entity.x = 0.5f;
        entity.y = 0.2f;
        entity.z = -0.3f;
        MemoryGraphScene.Node memory = new MemoryGraphScene.Node(
                "mem:1", "Souvenir", MemoryGraphScene.NodeKind.MEMORY, "session", 0.7);
        java.util.Map<String, MemoryGraphScene.Node> nodes = new java.util.HashMap<>();
        nodes.put(entity.id, entity);
        nodes.put(memory.id, memory);
        MemoryGraphScene.Edge edge = new MemoryGraphScene.Edge(
                memory.id, entity.id, 0.7, false, false);
        MemoryGraphScene.layoutMemories(
                Collections.singletonList(memory), nodes, Collections.singletonList(edge));
        float d = dist(memory, entity);
        assertTrue(d < 1.4f);
        assertTrue(d > 0.3f);
    }

    @Test
    public void layoutMemories_siblingsSpreadAroundSameAnchor() {
        MemoryGraphScene.Node entity = new MemoryGraphScene.Node(
                "entity", "Pégase", MemoryGraphScene.NodeKind.ENTITY, "project", 0.9);
        entity.x = 1.2f;
        entity.y = 0.4f;
        entity.z = -0.6f;
        MemoryGraphScene.Node m1 = new MemoryGraphScene.Node(
                "mem:1", "A", MemoryGraphScene.NodeKind.MEMORY, "session", 0.7);
        MemoryGraphScene.Node m2 = new MemoryGraphScene.Node(
                "mem:2", "B", MemoryGraphScene.NodeKind.MEMORY, "session", 0.7);
        MemoryGraphScene.Node m3 = new MemoryGraphScene.Node(
                "mem:3", "C", MemoryGraphScene.NodeKind.MEMORY, "session", 0.7);
        java.util.Map<String, MemoryGraphScene.Node> nodes = new java.util.HashMap<>();
        nodes.put(entity.id, entity);
        nodes.put(m1.id, m1);
        nodes.put(m2.id, m2);
        nodes.put(m3.id, m3);
        java.util.List<MemoryGraphScene.Edge> edges = Arrays.asList(
                new MemoryGraphScene.Edge(m1.id, entity.id, 0.7, false, false),
                new MemoryGraphScene.Edge(m2.id, entity.id, 0.7, false, false),
                new MemoryGraphScene.Edge(m3.id, entity.id, 0.7, false, false));
        MemoryGraphScene.layoutMemories(Arrays.asList(m1, m2, m3), nodes, edges);
        assertTrue(dist(m1, m2) > 0.55f);
        assertTrue(dist(m1, m3) > 0.55f);
        assertTrue(dist(m2, m3) > 0.55f);
    }

    @Test
    public void layoutMemories_orphansNotStackedAtOrigin() {
        MemoryGraphScene.Node m1 = new MemoryGraphScene.Node(
                "mem:1", "A", MemoryGraphScene.NodeKind.MEMORY, "session", 0.7);
        MemoryGraphScene.Node m2 = new MemoryGraphScene.Node(
                "mem:2", "B", MemoryGraphScene.NodeKind.MEMORY, "session", 0.7);
        MemoryGraphScene.layoutMemories(
                Arrays.asList(m1, m2),
                Collections.emptyMap(),
                Collections.emptyList());
        assertTrue(dist(m1, m2) > 1.0f);
    }

    private static float dist(MemoryGraphScene.Node a, MemoryGraphScene.Node b) {
        float dx = a.x - b.x;
        float dy = a.y - b.y;
        float dz = a.z - b.z;
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
