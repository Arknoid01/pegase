package com.pegasuscorp.orbe.memory;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class MemoryScorerTest {

    @Test
    public void keywordScore_boostsEntityMatch() {
        MemoryEntry entry = new MemoryEntry("projects", "Fableris city builder en cours", 0.8, "2026-07-28");
        double withEntity = MemoryScorer.keywordScore(entry, "projet", Collections.singletonList("Fableris"));
        double without = MemoryScorer.keywordScore(entry, "projet", null);
        assertTrue("entité devrait booster le score", withEntity > without);
    }

    @Test
    public void compositeSemantic_prefersHighCosineAndEntity() {
        MemoryEntry coldRoom = new MemoryEntry("projects", "Chambre froide stock viande", 0.9, "2026-07-28");
        MemoryEntry playlist = new MemoryEntry("prefs", "Playlist Spotify électro", 0.7, "2026-01-01");

        double coldScore = MemoryScorer.compositeSemantic(coldRoom, 0.36f, null);
        double playlistScore = MemoryScorer.compositeSemantic(playlist, 0.20f, null);

        assertTrue("chambre froide devrait scorer plus haut", coldScore > playlistScore);
    }

    @Test
    public void compositeSemantic_entityBoostCanReorder() {
        MemoryEntry saloir = new MemoryEntry("projects", "Livraison Le Saloir palette porc", 0.8, "2026-01-01");
        MemoryEntry cold = new MemoryEntry("projects", "Chambre froide stock viande", 0.9, "2026-01-01");

        double saloirWithEntity = MemoryScorer.compositeSemantic(
                saloir, 0.30f, Collections.singletonList("Saloir"));
        double coldWithEntity = MemoryScorer.compositeSemantic(
                cold, 0.36f, Collections.singletonList("Saloir"));

        assertTrue("boost entité Saloir devrait remonter la livraison",
                saloirWithEntity > coldWithEntity);
    }

    @Test
    public void recencyBoost_recentMemoryScoresHigher() {
        MemoryEntry recent = new MemoryEntry("general", "test", 0.5, "2026-07-28");
        MemoryEntry old = new MemoryEntry("general", "test", 0.5, "2020-01-01");
        assertTrue(MemoryScorer.recencyBoost(recent.createdAt) > MemoryScorer.recencyBoost(old.createdAt));
    }

    @Test
    public void graphEntityBoost_linkedEntityScoresHigher() {
        MemoryEntry linked = new MemoryEntry("projects", "Note Fableris", 0.7, "2026-01-01");
        linked.entityIds.add("project_fableris");
        double withGraph = MemoryScorer.compositeSemantic(
                linked, 0.25f, null, Collections.singletonList("project_fableris"));
        double without = MemoryScorer.compositeSemantic(linked, 0.25f, null, (List<String>) null);
        assertTrue(withGraph > without);
    }

    @Test
    public void graphEntityBoost_hop2_lowerThanDirect() {
        MemoryEntry linked = new MemoryEntry("projects", "Note téléphone", 0.7, "2026-01-01");
        linked.entityIds.add("device_nothing_phone");
        EntityGraphStore.EntityReach reach = new EntityGraphStore.EntityReach();
        reach.hop0.add("project_fableris");
        reach.hop2.add("device_nothing_phone");
        double hop2 = MemoryScorer.graphEntityBoost(linked, reach);
        reach.hop2.clear();
        reach.hop0.add("device_nothing_phone");
        double direct = MemoryScorer.graphEntityBoost(linked, reach);
        assertTrue(direct > hop2);
        assertEquals(MemoryGraph.GRAPH_LINK_BOOST_HOP2, hop2, 0.001);
    }
}
