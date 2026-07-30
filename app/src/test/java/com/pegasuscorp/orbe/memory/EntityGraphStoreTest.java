package com.pegasuscorp.orbe.memory;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class EntityGraphStoreTest {

    private Context ctx;

    @Before
    public void setUp() {
        EntityGraphStore.resetInstanceForTests();
        ctx = RuntimeEnvironment.getApplication();
        File memDir = new File(ctx.getFilesDir(), "memory");
        if (memDir.exists()) {
            File edges = new File(memDir, "entity_edges.json");
            if (edges.exists()) edges.delete();
        }
    }

    @Test
    public void seedDefaults_containsPegaseRunsOnPhone() {
        EntityGraphStore graph = EntityGraphStore.getInstance(ctx);
        assertFalse(graph.getAllEdges().isEmpty());
        assertTrue(graph.neighbors("project_pegase").contains("device_nothing_phone"));
    }

    @Test
    public void expand_twoHops_reachesRelatedProject() {
        EntityGraphStore graph = EntityGraphStore.getInstance(ctx);
        EntityGraphStore.EntityReach reach = graph.expand(
                Collections.singletonList("device_nothing_phone"), 2);
        assertTrue(reach.hop0.contains("device_nothing_phone"));
        assertTrue(reach.hop1.contains("project_pegase"));
        assertTrue(reach.hop2.contains("project_fableris"));
    }

    @Test
    public void inferFromMemory_doesNotDuplicateExistingEdge() {
        EntityGraphStore graph = EntityGraphStore.getInstance(ctx);
        int before = graph.getAllEdges().size();
        MemoryEntry entry = new MemoryEntry(
                "projects", "Pégase sur mon Nothing Phone", 0.9, "2026-07-30");
        entry.entityIds.addAll(Arrays.asList("project_pegase", "device_nothing_phone"));
        graph.inferFromMemory(ctx, entry);
        assertEquals(before, graph.getAllEdges().size());
    }

    @Test
    public void hopDistance_reflectsExpansion() {
        EntityGraphStore graph = EntityGraphStore.getInstance(ctx);
        EntityGraphStore.EntityReach reach = graph.expand(
                Collections.singletonList("project_fableris"), 2);
        assertEquals(0, reach.hopDistance("project_fableris"));
        assertEquals(1, reach.hopDistance("project_pegase"));
        assertEquals(2, reach.hopDistance("device_nothing_phone"));
    }
}
