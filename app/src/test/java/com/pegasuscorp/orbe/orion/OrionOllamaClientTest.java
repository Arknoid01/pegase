package com.pegasuscorp.orbe.orion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class OrionOllamaClientTest {

    @Test
    public void pickModel_prefersExactThenQwenVariantThenFirst() {
        assertNull(OrionOllamaClient.pickModel(Collections.emptyList()));
        assertFalse(OrionOllamaClient.hasUsableModel(Collections.emptyList()));
        assertEquals(OrionOllamaClient.MODEL,
                OrionOllamaClient.pickModel(Arrays.asList(
                        "llama3:8b", OrionOllamaClient.MODEL)));
        assertTrue(OrionOllamaClient.hasUsableModel(
                Collections.singletonList(OrionOllamaClient.MODEL)));
        assertEquals("qwen3-coder:latest",
                OrionOllamaClient.pickModel(Arrays.asList(
                        "llama3:8b", "qwen3-coder:latest")));
        assertEquals("mistral:7b",
                OrionOllamaClient.pickModel(Collections.singletonList("mistral:7b")));
    }

    @Test
    public void sanitizeErrorBody_stripsRunPodHtml() {
        String html = "<!DOCTYPE html><html><title>Waiting for service to respond – RunPod</title>";
        assertEquals("Waiting for service to respond (RunPod)",
                OrionOllamaClient.sanitizeErrorBody(html));
        assertEquals("short", OrionOllamaClient.sanitizeErrorBody("short"));
    }
}
