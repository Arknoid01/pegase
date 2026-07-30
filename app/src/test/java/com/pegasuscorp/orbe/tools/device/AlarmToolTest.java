package com.pegasuscorp.orbe.tools.device;

import org.junit.Test;

import static org.junit.Assert.*;

public class AlarmToolTest {

    @Test
    public void parseTime_standard() {
        int[] t = AlarmTool.parseTime("7h30");
        assertNotNull(t);
        assertEquals(7, t[0]);
        assertEquals(30, t[1]);
    }

    @Test
    public void parseTime_withA() {
        int[] t = AlarmTool.parseTime("à 10h");
        assertNotNull(t);
        assertEquals(10, t[0]);
        assertEquals(0, t[1]);
    }
}
