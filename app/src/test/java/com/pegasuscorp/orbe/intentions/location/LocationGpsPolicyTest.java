package com.pegasuscorp.orbe.intentions.location;

import org.junit.Test;

import static org.junit.Assert.*;

public class LocationGpsPolicyTest {

    private static final long NOW = 10_000_000L;

    @Test
    public void stationary_gpsOff() {
        assertFalse(LocationGpsPolicy.shouldGpsBeOn(true, true, false, 0f, 20f, 0L, NOW, false));
    }

    @Test
    public void networkSpeedNearDriving_wakesGps() {
        assertTrue(LocationGpsPolicy.shouldGpsBeOn(true, true, false, 13f, 20f, NOW, NOW, false));
    }

    @Test
    public void autoDriveDisabled_neverGps() {
        assertFalse(LocationGpsPolicy.shouldGpsBeOn(false, true, true, 80f, 20f, NOW, NOW, true));
    }

    @Test
    public void noNetwork_gpsFallback() {
        assertTrue(LocationGpsPolicy.shouldGpsBeOn(false, false, false, 0f, 20f, 0L, NOW, false));
    }

    @Test
    public void driveActive_keepsGps() {
        assertTrue(LocationGpsPolicy.shouldGpsBeOn(true, true, true, 0f, 20f, 0L, NOW, true));
    }

    @Test
    public void slowForFiveMinutes_gpsOff() {
        long recent = NOW - 60_000L;
        long old = NOW - LocationGpsPolicy.GPS_IDLE_OFF_MS - 1L;
        assertTrue(LocationGpsPolicy.shouldGpsBeOn(true, true, false, 3f, 20f, recent, NOW, true));
        assertFalse(LocationGpsPolicy.shouldGpsBeOn(true, true, false, 3f, 20f, old, NOW, true));
    }

    @Test
    public void antennaJumpWithinAccuracy_noSpeed() {
        assertEquals(0f, LocationGpsPolicy.derivedSpeedMps(400f, 60_000L, 300f, 200f), 0f);
    }

    @Test
    public void realMovement_givesSpeed() {
        // 900 m en 60 s avec ±50 m chacun → 800/60 ≈ 13,3 m/s ≈ 48 km/h
        assertEquals(13.33f, LocationGpsPolicy.derivedSpeedMps(900f, 60_000L, 50f, 50f), 0.05f);
    }

    @Test
    public void tooCloseOrTooFarInTime_noSpeed() {
        assertEquals(0f, LocationGpsPolicy.derivedSpeedMps(900f, 5_000L, 10f, 10f), 0f);
        assertEquals(0f, LocationGpsPolicy.derivedSpeedMps(9000f, 3_600_000L, 10f, 10f), 0f);
    }
}
