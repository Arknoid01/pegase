package com.pegasuscorp.orbe.tools;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class EmptyToolParamsTest {

    @Test
    public void seed_deviceEmpty_injectsQuery() throws Exception {
        JSONObject out = EmptyToolParams.seedUtteranceIfEmpty(
                "device", new JSONObject(), "quelle est ma batterie ?");
        assertEquals("quelle est ma batterie ?", out.getString("query"));
        assertEquals("quelle est ma batterie ?", out.getString("utterance"));
    }

    @Test
    public void seed_keepsExistingAction() throws Exception {
        JSONObject in = new JSONObject().put("action", "battery");
        JSONObject out = EmptyToolParams.seedUtteranceIfEmpty(
                "device", in, "quelle heure");
        assertEquals("battery", out.getString("action"));
        assertFalse(out.has("query"));
    }

    @Test
    public void seed_ignoresOtherTools() {
        JSONObject out = EmptyToolParams.seedUtteranceIfEmpty(
                "search", new JSONObject(), "prix bitcoin");
        assertEquals(0, out.length());
    }
}
