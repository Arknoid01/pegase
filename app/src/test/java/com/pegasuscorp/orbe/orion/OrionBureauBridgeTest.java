package com.pegasuscorp.orbe.orion;

import org.junit.Test;

import static org.junit.Assert.*;

public class OrionBureauBridgeTest {

    @Test
    public void noPending_none() {
        assertEquals(OrionBureauBridge.Action.NONE,
                OrionBureauBridge.decide(OrionStatus.READY, false, false));
    }

    @Test
    public void ready_submits() {
        assertEquals(OrionBureauBridge.Action.SUBMIT,
                OrionBureauBridge.decide(OrionStatus.READY, true, false));
    }

    @Test
    public void generating_waits() {
        assertEquals(OrionBureauBridge.Action.WAIT,
                OrionBureauBridge.decide(OrionStatus.READY, true, true));
    }

    @Test
    public void offline_launchAndWait() {
        assertEquals(OrionBureauBridge.Action.LAUNCH_AND_WAIT,
                OrionBureauBridge.decide(OrionStatus.OFFLINE, true, false));
    }

    @Test
    public void starting_waits() {
        assertEquals(OrionBureauBridge.Action.WAIT,
                OrionBureauBridge.decide(OrionStatus.STARTING, true, false));
    }

    @Test
    public void busy_waits() {
        assertEquals(OrionBureauBridge.Action.WAIT,
                OrionBureauBridge.decide(OrionStatus.BUSY, true, false));
    }
}
