package com.pegasuscorp.orbe.voice;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LockScreenToolPolicyTest {

    @Test
    public void calculatorAllowedOnLock() {
        assertTrue(LockScreenToolPolicy.isWhitelistedOnLockScreen("calcul", null));
    }

    @Test
    public void navigationBlockedOnLock() {
        assertFalse(LockScreenToolPolicy.isWhitelistedOnLockScreen(
                "navigation", "{\"navigation\":{\"destination\":\"Paris\"}}"));
    }

    @Test
    public void agendaRequiresSpeakerVerify() {
        assertTrue(LockScreenToolPolicy.requiresSpeakerVerifyOnLock("agenda", null));
        assertTrue(LockScreenToolPolicy.requiresSpeakerVerifyOnLock("calendar", null));
        assertFalse(LockScreenToolPolicy.requiresSpeakerVerifyOnLock("timer", null));
    }

    @Test
    public void calendarAllowedOnLock() {
        assertTrue(LockScreenToolPolicy.isWhitelistedOnLockScreen("calendar", null));
    }
}
