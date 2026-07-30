package com.pegasuscorp.orbe.chat;

import org.junit.Test;

import static org.junit.Assert.*;

public class OpenRouterVisionClientTest {

    @Test
    public void defaultVisionModel_isQwenVl() {
        assertEquals("qwen/qwen2.5-vl-72b-instruct",
                OpenRouterVisionClient.DEFAULT_VISION_MODEL);
    }
}
