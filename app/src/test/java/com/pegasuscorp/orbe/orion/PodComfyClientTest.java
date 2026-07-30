package com.pegasuscorp.orbe.orion;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class PodComfyClientTest {

    @Test
    public void userMessage_running() throws Exception {
        assertEquals("ComfyUI en cours de démarrage (8188)",
                PodComfyClient.userMessage(new JSONObject()
                        .put("ok", true)
                        .put("comfy", "running")
                        .put("list", "running")));
    }

    @Test
    public void userMessage_already() throws Exception {
        assertEquals("ComfyUI déjà lancé",
                PodComfyClient.userMessage(new JSONObject()
                        .put("ok", true)
                        .put("comfy", "already")
                        .put("list", "already")));
    }

    @Test
    public void userMessage_errorPayload() throws Exception {
        String msg = PodComfyClient.userMessage(new JSONObject()
                .put("ok", false)
                .put("error", "ComfyUI absent"));
        assertTrue(msg.contains("absent"));
    }
}
