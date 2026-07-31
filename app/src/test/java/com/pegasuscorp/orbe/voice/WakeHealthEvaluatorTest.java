package com.pegasuscorp.orbe.voice;

import org.junit.Test;

import static org.junit.Assert.*;

public class WakeHealthEvaluatorTest {

    @Test
    public void offWhenNotWantingListen() {
        assertEquals(WakeHealthStatus.OFF,
                WakeHealthEvaluator.evaluate(false, false, true, true));
    }

    @Test
    public void problemWhenCrashGuardTripped() {
        assertEquals(WakeHealthStatus.PROBLEM,
                WakeHealthEvaluator.evaluate(true, true, false, true));
    }

    @Test
    public void listeningWhenKwsRunning() {
        assertEquals(WakeHealthStatus.LISTENING,
                WakeHealthEvaluator.evaluate(true, false, true, true));
    }

    @Test
    public void problemWhenModelReadyButKwsNotRunning() {
        assertEquals(WakeHealthStatus.PROBLEM,
                WakeHealthEvaluator.evaluate(true, false, false, true));
    }

    @Test
    public void listeningWhileModelDownloading() {
        assertEquals(WakeHealthStatus.LISTENING,
                WakeHealthEvaluator.evaluate(true, false, false, false));
    }
}
