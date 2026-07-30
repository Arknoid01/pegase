package com.pegasuscorp.orbe.memory;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.*;

public class MemoryEntryGraphTest {

    @Test
    public void jsonRoundTrip_preservesGraphFields() throws Exception {
        MemoryEntry entry = new MemoryEntry("projects", "Test Fableris", 0.8, "2026-07-30");
        entry.entityIds.add("project_fableris");
        entry.relatedMemoryKeys.add("abc123");

        JSONObject json = entry.toJson();
        MemoryEntry restored = MemoryEntry.fromJson(json);

        assertEquals("project_fableris", restored.entityIds.get(0));
        assertEquals("abc123", restored.relatedMemoryKeys.get(0));
        assertNotNull(entry.memoryKey());
        assertEquals(entry.memoryKey(), restored.memoryKey());
    }

    @Test
    public void fromJson_withoutGraphFields_defaultsEmpty() throws Exception {
        MemoryEntry entry = MemoryEntry.fromJson(new JSONObject()
                .put("category", "general")
                .put("content", "hello")
                .put("importance", 0.5)
                .put("createdAt", "2026-01-01"));
        assertTrue(entry.entityIds.isEmpty());
        assertTrue(entry.relatedMemoryKeys.isEmpty());
    }
}
