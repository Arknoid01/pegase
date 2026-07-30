package com.pegasuscorp.orbe.memory;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class MemoryGraphTest {

    @Test
    public void expandCandidates_includesSharedEntityMemory() {
        MemoryEntry fabDelivery = new MemoryEntry(
                "projects", "Livraison Le Saloir palette porc lundi", 0.8, "2026-01-01");
        fabDelivery.entityIds.add("project_fableris");

        MemoryEntry fabNote = new MemoryEntry(
                "projects", "Budget marketing Fableris Q3", 0.75, "2026-01-01");
        fabNote.entityIds.add("project_fableris");

        MemoryEntry unrelated = new MemoryEntry(
                "prefs", "Playlist Spotify électro", 0.7, "2026-01-01");

        List<MemoryEntry> all = Arrays.asList(fabDelivery, fabNote, unrelated);
        List<MemoryEntry> ranked = Collections.singletonList(fabDelivery);

        List<MemoryEntry> expanded = MemoryGraph.expandCandidates(
                ranked, all, Collections.singletonList("project_fableris"), 3);

        assertEquals(2, expanded.size());
        assertTrue(expanded.contains(fabNote));
    }

    @Test
    public void linkRelated_bidirectional() {
        MemoryEntry a = new MemoryEntry("a", "one", 0.5, "2026-01-01");
        MemoryEntry b = new MemoryEntry("b", "two", 0.5, "2026-01-01");
        MemoryGraph.linkRelated(a, b);
        assertTrue(a.relatedMemoryKeys.contains(b.memoryKey()));
        assertTrue(b.relatedMemoryKeys.contains(a.memoryKey()));
    }

    @Test
    public void expandCandidates_followsRelatedMemoryKeys() {
        MemoryEntry a = new MemoryEntry("projects", "Alpha", 0.9, "2026-01-01");
        MemoryEntry b = new MemoryEntry("projects", "Beta", 0.8, "2026-01-01");
        MemoryGraph.linkRelated(a, b);

        List<MemoryEntry> expanded = MemoryGraph.expandCandidates(
                Collections.singletonList(a),
                Arrays.asList(a, b),
                Collections.emptyList(),
                2);

        assertTrue(expanded.contains(b));
    }
}
