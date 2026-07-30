package com.pegasuscorp.orbe.memory;

import org.junit.Test;

import static org.junit.Assert.*;

public class EntityEdgeTest {

    @Test
    public void defaultWeight_runsOn_isHigh() {
        assertEquals(0.95, EntityEdge.defaultWeight(EntityEdge.TYPE_RUNS_ON), 0.001);
    }

    @Test
    public void defaultWeight_prefers_isLower() {
        assertTrue(EntityEdge.defaultWeight(EntityEdge.TYPE_PREFERS)
                < EntityEdge.defaultWeight(EntityEdge.TYPE_RUNS_ON));
    }

    @Test
    public void formatWeight_twoDecimals() {
        assertEquals("0.42", EntityEdge.formatWeight(0.42));
        assertEquals("0.95", EntityEdge.formatWeight(0.95));
    }
}
