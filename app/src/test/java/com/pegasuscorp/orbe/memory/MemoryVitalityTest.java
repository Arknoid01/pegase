package com.pegasuscorp.orbe.memory;

import org.junit.Test;

import static org.junit.Assert.*;

public class MemoryVitalityTest {

    @Test
    public void decayedImportance_noDecayWithinGrace() {
        long now = 1_000_000_000_000L;
        long lastUsed = now - (long) (MemoryVitality.GRACE_DAYS * 0.5 * 86_400_000L);
        double w = MemoryVitality.decayedImportance(0.80, lastUsed, now, false);
        assertEquals(0.80, w, 0.001);
    }

    @Test
    public void decayedImportance_halvesAfterOneYearBeyondGrace() {
        long now = 1_000_000_000_000L;
        long lastUsed = now - (long) ((MemoryVitality.GRACE_DAYS + 365) * 86_400_000L);
        double w = MemoryVitality.decayedImportance(0.80, lastUsed, now, false);
        assertEquals(0.40, w, 0.02);
    }

    @Test
    public void decayedImportance_frozenUnchanged() {
        long now = 1_000_000_000_000L;
        long lastUsed = now - (long) (800 * 86_400_000L);
        assertEquals(0.95, MemoryVitality.decayedImportance(0.95, lastUsed, now, true), 0.001);
    }

    @Test
    public void memoryEntry_touchRetrievalIncreasesImportance() {
        MemoryEntry entry = new MemoryEntry("session", "Test fact", 0.60, "2026-07-30");
        entry.touchRetrieval(42L);
        assertTrue(entry.importance > 0.60);
        assertEquals(42L, entry.lastUsedAtMs);
        assertEquals(entry.importance, entry.importanceAtLastUse, 0.001);
    }

    @Test
    public void memoryEntry_applyDecay_reducesUnusedImportance() {
        long now = 1_000_000_000_000L;
        long lastUsed = now - (long) ((MemoryVitality.GRACE_DAYS + 200) * 86_400_000L);
        MemoryEntry entry = new MemoryEntry("session", "Old fact", 0.70, "2026-01-01");
        entry.frozen = false;
        entry.lastUsedAtMs = lastUsed;
        entry.importanceAtLastUse = 0.70;
        assertTrue(entry.applyDecay(now));
        assertTrue(entry.importance < 0.70);
    }
}
