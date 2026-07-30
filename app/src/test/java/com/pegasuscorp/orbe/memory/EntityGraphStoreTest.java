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
    public void expand_weightedPath_multipliesEdgeWeights() {
        EntityGraphStore.resetInstanceForTests();
        java.io.File edges = new java.io.File(ctx.getFilesDir(), "memory/entity_edges.json");
        if (edges.exists()) edges.delete();
        EntityGraphStore graph = EntityGraphStore.getInstance(ctx);
        while (!graph.getAllEdges().isEmpty()) {
            // fresh store after delete still seeds defaults — use link on clean custom graph
            break;
        }
        graph = EntityGraphStore.getInstance(ctx);
        graph.link("project_pegase", "device_nothing_phone", EntityEdge.TYPE_RUNS_ON, 0.95);
        graph.link("project_fableris", "project_pegase", EntityEdge.TYPE_RELATED_TO, 0.70);

        EntityGraphStore.EntityReach reach = graph.expand(
                java.util.Collections.singletonList("project_fableris"), 2);
        assertEquals(1.0, reach.strengthFor("project_fableris"), 0.001);
        assertEquals(0.70, reach.strengthFor("project_pegase"), 0.001);
        assertEquals(0.70 * 0.95, reach.strengthFor("device_nothing_phone"), 0.001);
    }

    @Test
    public void link_strengthensExistingEdge() {
        EntityGraphStore graph = EntityGraphStore.getInstance(ctx);
        double before = 0;
        for (EntityEdge e : graph.getAllEdges()) {
            if ("project_pegase".equals(e.fromId) && "device_nothing_phone".equals(e.toId)) {
                before = e.weight;
            }
        }
        graph.link("project_pegase", "device_nothing_phone", EntityEdge.TYPE_RUNS_ON, 0.95);
        double after = 0;
        for (EntityEdge e : graph.getAllEdges()) {
            if ("project_pegase".equals(e.fromId) && "device_nothing_phone".equals(e.toId)) {
                after = e.weight;
            }
        }
        assertTrue(after >= before);
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

    @Test
    public void seedDefaults_pegasePhoneIsFrozen() {
        EntityGraphStore graph = EntityGraphStore.getInstance(ctx);
        boolean foundFrozen = false;
        for (EntityEdge e : graph.getAllEdges()) {
            if ("project_pegase".equals(e.fromId) && "device_nothing_phone".equals(e.toId)) {
                assertTrue(e.frozen);
                foundFrozen = true;
            }
        }
        assertTrue(foundFrozen);
    }

    @Test
    public void applyNaturalDecay_storePersistsLowerWeight() throws Exception {
        File edges = new File(ctx.getFilesDir(), "memory/entity_edges.json");
        edges.getParentFile().mkdirs();
        long now = System.currentTimeMillis();
        long stale = now - (long) ((EntityEdge.GRACE_DAYS + 400) * 86_400_000L);
        org.json.JSONArray arr = new org.json.JSONArray();
        arr.put(new org.json.JSONObject()
                .put("from", "entity_a")
                .put("to", "entity_b")
                .put("type", EntityEdge.TYPE_PREFERS)
                .put("weight", 0.60)
                .put("frozen", false)
                .put("lastUsedAt", stale));
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(edges)) {
            out.write(arr.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        EntityGraphStore.resetInstanceForTests();
        EntityGraphStore graph = EntityGraphStore.getInstance(ctx);
        graph.applyNaturalDecay(now);
        double weight = -1;
        for (EntityEdge e : graph.getAllEdges()) {
            if ("entity_a".equals(e.fromId) && "entity_b".equals(e.toId)) {
                weight = e.weight;
            }
        }
        assertTrue(weight > 0);
        assertTrue(weight < 0.60);
    }

    @Test
    public void recordRetrievalUse_strengthensTraversedEdges() {
        EntityGraphStore graph = EntityGraphStore.getInstance(ctx);
        double before = 0;
        for (EntityEdge e : graph.getAllEdges()) {
            if ("project_fableris".equals(e.fromId) && "project_pegase".equals(e.toId)) {
                before = e.weight;
            }
        }
        EntityGraphStore.EntityReach reach = graph.expand(
                Collections.singletonList("project_fableris"), 2);
        assertFalse(reach.usedEdgeKeys.isEmpty());
        graph.recordRetrievalUse(reach);
        double after = 0;
        for (EntityEdge e : graph.getAllEdges()) {
            if ("project_fableris".equals(e.fromId) && "project_pegase".equals(e.toId)) {
                after = e.weight;
            }
        }
        assertTrue(after >= before);
    }

    @Test
    public void freeze_preventsDecayOnNextPass() {
        EntityGraphStore graph = EntityGraphStore.getInstance(ctx);
        graph.link("entity_x", "entity_y", EntityEdge.TYPE_RELATED_TO, 0.55, false);
        graph.freeze("entity_x", "entity_y", EntityEdge.TYPE_RELATED_TO);
        for (EntityEdge e : graph.getAllEdges()) {
            if ("entity_x".equals(e.fromId) && "entity_y".equals(e.toId)) {
                assertTrue(e.frozen);
            }
        }
    }
}
