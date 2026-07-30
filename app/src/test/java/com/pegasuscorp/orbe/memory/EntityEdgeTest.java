package com.pegasuscorp.orbe.memory;

import org.junit.Test;

import static org.junit.Assert.*;

public class EntityEdgeTest {

    @Test
    public void defaultWeight_runsOnStrongerThanPrefers() {
        assertEquals(0.95, EntityEdge.defaultWeight(EntityEdge.TYPE_RUNS_ON), 0.001);
    }

    @Test
    public void defaultWeight_prefersWeakerThanRunsOn() {
        assertTrue(EntityEdge.defaultWeight(EntityEdge.TYPE_PREFERS)
                < EntityEdge.defaultWeight(EntityEdge.TYPE_RUNS_ON));
    }

    @Test
    public void formatWeight_twoDecimals() {
        assertEquals("0.42", EntityEdge.formatWeight(0.42));
        assertEquals("0.95", EntityEdge.formatWeight(0.95));
    }

    @Test
    public void defaultFrozen_runsOnAndPartOf() {
        assertTrue(EntityEdge.defaultFrozen(EntityEdge.TYPE_RUNS_ON));
        assertTrue(EntityEdge.defaultFrozen(EntityEdge.TYPE_PART_OF));
        assertFalse(EntityEdge.defaultFrozen(EntityEdge.TYPE_PREFERS));
    }

    @Test
    public void decayedWeight_noDecayWithinGrace() {
        long now = 1_000_000_000_000L;
        long lastUsed = now - (long) (EntityEdge.GRACE_DAYS * 0.5 * 86_400_000L);
        double w = EntityEdge.decayedWeight(0.80, lastUsed, now, false);
        assertEquals(0.80, w, 0.001);
    }

    @Test
    public void decayedWeight_slowDecayAfterGrace() {
        long now = 1_000_000_000_000L;
        long lastUsed = now - (long) ((EntityEdge.GRACE_DAYS + 365) * 86_400_000L);
        double w = EntityEdge.decayedWeight(0.80, lastUsed, now, false);
        assertEquals(0.40, w, 0.02);
    }

    @Test
    public void decayedWeight_frozenUnchanged() {
        long now = 1_000_000_000_000L;
        long lastUsed = now - (long) (800 * 86_400_000L);
        double w = EntityEdge.decayedWeight(0.95, lastUsed, now, true);
        assertEquals(0.95, w, 0.001);
    }

    @Test
    public void decayedWeight_idempotentForSameDay() {
        long now = 1_000_000_000_000L;
        long lastUsed = now - (long) ((EntityEdge.GRACE_DAYS + 100) * 86_400_000L);
        double once = EntityEdge.decayedWeight(0.80, lastUsed, now, false);
        double twice = EntityEdge.decayedWeight(0.80, lastUsed, now, false);
        assertEquals(once, twice, 0.0001);
    }

    @Test
    public void toJson_roundTripsFrozenAndLastUsed() throws Exception {
        EntityEdge edge = new EntityEdge("a", "b", EntityEdge.TYPE_RUNS_ON, 0.95, true, 42L);
        EntityEdge back = EntityEdge.fromJson(edge.toJson());
        assertEquals(0.95, back.weight, 0.001);
        assertTrue(back.frozen);
        assertEquals(42L, back.lastUsedAtMs);
    }
}
