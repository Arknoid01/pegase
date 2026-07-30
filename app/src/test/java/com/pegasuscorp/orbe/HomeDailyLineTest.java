package com.pegasuscorp.orbe;

import org.junit.Test;

import static org.junit.Assert.*;

public class HomeDailyLineTest {

    @Test
    public void pool_isLargeEnough() {
        assertTrue(HomeDailyLine.poolSize() >= 30);
    }

    @Test
    public void candidatesForNow_notEmpty() {
        assertFalse(HomeDailyLine.candidatesForNow().isEmpty());
    }

    @Test
    public void slotNow_inRange() {
        int slot = HomeDailyLine.slotNow();
        assertTrue(slot >= 1 && slot <= 5);
    }
}
