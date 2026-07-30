package com.pegasuscorp.orbe.chat;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class MultiProviderBackendTest {

    private Context ctx;
    private AtomicLong now;

    @Before
    public void setUp() {
        ctx = RuntimeEnvironment.getApplication();
        now = new AtomicLong(1_000_000L);
        ApiKeyStore.setGroqKey(ctx, "gsk_test");
        ApiKeyStore.setCerebrasKey(ctx, "csk_test");
        ApiKeyStore.setOpenRouterKey(ctx, "sk-or-test");
        ApiKeyStore.setGeminiKey(ctx, "AIza_should_not_appear");
        CloudModelStore.setActiveProvider(ctx, CloudModelStore.PROVIDER_GROQ);
        CloudModelStore.setGroqModelId(ctx, CloudModelStore.GROQ_GPT_OSS_120B);
    }

    @Test
    public void providerChain_excludesGemini() {
        List<LlmProvider> all = ProviderChain.buildAll(ctx, false);
        assertEquals(3, all.size());
        assertEquals(LlmProvider.ID_GROQ, all.get(0).id);
        assertEquals(LlmProvider.ID_CEREBRAS, all.get(1).id);
        assertEquals(LlmProvider.ID_OPENROUTER, all.get(2).id);
        for (LlmProvider p : all) {
            assertFalse(p.id.contains("gemini"));
        }
    }

    @Test
    public void providerChain_skipsMissingKeys() {
        ApiKeyStore.setCerebrasKey(ctx, "");
        List<LlmProvider> chain = ProviderChain.build(ctx, false);
        assertEquals(2, chain.size());
        assertEquals(LlmProvider.ID_GROQ, chain.get(0).id);
        assertEquals(LlmProvider.ID_OPENROUTER, chain.get(1).id);
    }

    @Test
    public void healthTracker_cooldownAfterTimeout() {
        ProviderHealthTracker health = new ProviderHealthTracker(now::get);
        LlmProvider groq = ProviderChain.buildAll(ctx, false).get(0);
        assertFalse(health.isUnhealthy(groq));
        health.markTimeout(groq);
        assertTrue(health.isUnhealthy(groq));
        now.addAndGet(ProviderHealthTracker.COOLDOWN_MS - 1);
        assertTrue(health.isUnhealthy(groq));
        now.addAndGet(2);
        assertFalse(health.isUnhealthy(groq));
    }

    @Test
    public void healthTracker_rateLimitUsesLongerCooldown() {
        ProviderHealthTracker health = new ProviderHealthTracker(now::get);
        LlmProvider groq = ProviderChain.buildAll(ctx, false).get(0);
        health.markRateLimit(groq, 5_000L);
        assertTrue(health.isUnhealthy(groq));
        now.addAndGet(30_000L);
        assertTrue(health.isUnhealthy(groq));
        now.addAndGet(ProviderHealthTracker.RATELIMIT_COOLDOWN_MS);
        assertFalse(health.isUnhealthy(groq));
    }

    @Test
    public void healthTracker_successClearsCooldown() {
        ProviderHealthTracker health = new ProviderHealthTracker(now::get);
        LlmProvider groq = ProviderChain.buildAll(ctx, false).get(0);
        health.markTimeout(groq);
        health.markSuccess(groq);
        assertFalse(health.isUnhealthy(groq));
    }

    @Test
    public void timeouts_groqLongerThanOthers() {
        assertEquals(15_000, LlmProvider.readTimeoutMsFor(LlmProvider.ID_GROQ, false));
        assertEquals(10_000, LlmProvider.readTimeoutMsFor(LlmProvider.ID_CEREBRAS, false));
        assertEquals(10_000, LlmProvider.readTimeoutMsFor(LlmProvider.ID_OPENROUTER, false));
        assertEquals(20_000, LlmProvider.readTimeoutMsFor(LlmProvider.ID_GROQ, true));
    }

    @Test
    public void factory_returnsMultiProviderWhenCloud() {
        com.pegasuscorp.orbe.llm.ModelStore.setUseLocalLlm(ctx, false);
        ChatBackend backend = ChatBackendFactory.create(ctx);
        assertTrue(backend instanceof MultiProviderBackend);
    }

    @Test
    public void expandTools_addsSearchTag() {
        java.util.EnumSet<com.pegasuscorp.orbe.tools.ToolTag> base =
                java.util.EnumSet.of(
                        com.pegasuscorp.orbe.tools.ToolTag.NOTEPAD,
                        com.pegasuscorp.orbe.tools.ToolTag.DEVICE);
        ChatSendOptions opts = ChatSendOptions.forText(base);
        ChatSendOptions expanded = MultiProviderBackend.expandToolsForMissingCall(opts,
                "Groq HTTP 400 : Tool search was not in request.tools");
        assertNotNull(expanded);
        assertTrue(expanded.allowedTools.contains(
                com.pegasuscorp.orbe.tools.ToolTag.SEARCH));
        assertTrue(expanded.allowedTools.contains(
                com.pegasuscorp.orbe.tools.ToolTag.NOTEPAD));
    }

    @Test
    public void expandTools_skipsUnknownTool() {
        ChatSendOptions opts = ChatSendOptions.forText(
                java.util.EnumSet.of(com.pegasuscorp.orbe.tools.ToolTag.DEVICE));
        assertNull(MultiProviderBackend.expandToolsForMissingCall(opts,
                "Tool google_search was not in request.tools"));
    }

    @Test
    public void expandTools_skipsSynthesisStep() {
        ChatSendOptions opts = ChatSendOptions.agenticStep(
                java.util.EnumSet.of(com.pegasuscorp.orbe.tools.ToolTag.DEVICE), false);
        assertNull(MultiProviderBackend.expandToolsForMissingCall(opts,
                "Tool search was not in request.tools"));
    }
}
