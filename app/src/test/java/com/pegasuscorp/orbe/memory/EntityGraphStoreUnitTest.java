package com.pegasuscorp.orbe.memory;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.*;

public class EntityGraphStoreUnitTest {

    @Test
    public void inferEdgeType_projectDevice_isRunsOn() {
        Entity project = new Entity("p1", Entity.TYPE_PROJECT, "Pégase", null, null);
        Entity device = new Entity("d1", Entity.TYPE_DEVICE, "Phone", null, null);
        assertEquals(EntityEdge.TYPE_RUNS_ON, EntityGraphStore.inferEdgeType(project, device));
    }

    @Test
    public void inferEdgeType_personProject_isWorksOn() {
        Entity person = new Entity("u1", Entity.TYPE_PERSON, "Yannick", null, null);
        Entity project = new Entity("p1", Entity.TYPE_PROJECT, "Fableris", null, null);
        assertEquals(EntityEdge.TYPE_WORKS_ON, EntityGraphStore.inferEdgeType(person, project));
    }

    @Test
    public void formatEdgeLabel_includesWeight() {
        EntityEdge edge = new EntityEdge("project_pegase", "device_nothing_phone",
                EntityEdge.TYPE_RUNS_ON, 0.95);
        String label = EntityGraphStore.formatEdgeLabel(null, edge);
        assertTrue(label.contains("0.95"));
        assertTrue(label.contains("(figé)"));
        assertTrue(label.contains("──"));
    }
}
