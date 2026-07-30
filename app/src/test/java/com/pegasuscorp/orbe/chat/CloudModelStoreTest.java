package com.pegasuscorp.orbe.chat;

import org.junit.Test;

import static org.junit.Assert.*;

public class CloudModelStoreTest {

    @Test
    public void groqFallbackChain_single120bWhenPreferred() {
        String[][] chain = CloudModelStore.groqFallbackChain(
                CloudModelStore.GROQ_GPT_OSS_120B);
        assertEquals(1, chain.length);
        assertEquals(CloudModelStore.GROQ_GPT_OSS_120B, chain[0][0]);
    }

    @Test
    public void groqFallbackChain_keepsManualPreferredThen120b() {
        String[][] chain = CloudModelStore.groqFallbackChain(
                CloudModelStore.GROQ_QWEN_36_27B);
        assertEquals(2, chain.length);
        assertEquals(CloudModelStore.GROQ_QWEN_36_27B, chain[0][0]);
        assertEquals(CloudModelStore.GROQ_GPT_OSS_120B, chain[1][0]);
    }

    @Test
    public void groqFallbackChain_skipsRetiredScout() {
        String[][] chain = CloudModelStore.groqFallbackChain(
                CloudModelStore.GROQ_LLAMA_4_SCOUT);
        assertEquals(1, chain.length);
        assertEquals(CloudModelStore.GROQ_GPT_OSS_120B, chain[0][0]);
    }

    @Test
    public void isGroqToolModel_catalog() {
        assertTrue(CloudModelStore.isGroqToolModel(CloudModelStore.GROQ_GPT_OSS_120B));
        assertTrue(CloudModelStore.isGroqToolModel(CloudModelStore.GROQ_GPT_OSS_20B));
        assertFalse(CloudModelStore.isGroqToolModel(CloudModelStore.GROQ_LLAMA_4_SCOUT));
    }

    @Test
    public void groqModels_excludesScout() {
        for (String[] entry : CloudModelStore.GROQ_MODELS) {
            assertFalse(CloudModelStore.GROQ_LLAMA_4_SCOUT.equals(entry[0]));
        }
    }
}
