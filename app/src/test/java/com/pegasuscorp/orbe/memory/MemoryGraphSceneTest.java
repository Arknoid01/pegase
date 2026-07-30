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
        float dist = (float) Math.sqrt(
                (memory.x - entity.x) * (memory.x - entity.x)
                        + (memory.y - entity.y) * (memory.y - entity.y)
                        + (memory.z - entity.z) * (memory.z - entity.z));
        assertTrue(dist < 0.6f);
        assertTrue(dist > 0.05f);
    }
}
