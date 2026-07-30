package com.pegasuscorp.orbe.voice.handlers;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class SystemIntentHandlerDeviceTest {

    @Test
    public void looksLikeDevice_timeDateBattery() {
        assertTrue(SystemIntentHandler.looksLikeDevice("quelle heure est il"));
        assertTrue(SystemIntentHandler.looksLikeDevice("quelle date"));
        assertTrue(SystemIntentHandler.looksLikeDevice("niveau de batterie"));
        assertFalse(SystemIntentHandler.looksLikeDevice("quelle est la capital de la france"));
    }

    @Test
    public void deviceActionFromFold() {
        assertEquals("battery", SystemIntentHandler.deviceActionFromFold("ma batterie"));
        assertEquals("date", SystemIntentHandler.deviceActionFromFold("on est quel jour"));
        assertEquals("time", SystemIntentHandler.deviceActionFromFold("quelle heure"));
    }
}
