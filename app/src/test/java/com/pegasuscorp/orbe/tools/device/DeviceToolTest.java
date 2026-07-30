package com.pegasuscorp.orbe.tools.device;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class DeviceToolTest {

    @Test
    public void resolveAction_batteryAliases() throws Exception {
        assertEquals("battery", DeviceTool.resolveAction(
                new JSONObject().put("action", "battery")));
        assertEquals("battery", DeviceTool.resolveAction(
                new JSONObject().put("action", "Batterie")));
        assertEquals("battery", DeviceTool.resolveAction(
                new JSONObject().put("query", "niveau de charge")));
    }

    @Test
    public void resolveAction_timeAndDate() throws Exception {
        assertEquals("time", DeviceTool.resolveAction(
                new JSONObject().put("action", "time")));
        assertEquals("time", DeviceTool.resolveAction(
                new JSONObject().put("action", "heure")));
        assertEquals("date", DeviceTool.resolveAction(
                new JSONObject().put("action", "date")));
    }

    @Test
    public void resolveAction_empty_notSilentTime() {
        assertEquals("", DeviceTool.resolveAction(new JSONObject()));
        assertEquals("", DeviceTool.resolveAction(null));
    }
}
