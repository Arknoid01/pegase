package com.pegasuscorp.orbe.orion;

import android.content.Context;

import com.pegasuscorp.orbe.chat.ApiKeyStore;
import com.pegasuscorp.orbe.contextstore.ContextSearchIndex;
import com.pegasuscorp.orbe.contextstore.ContextualFileStore;
import com.pegasuscorp.orbe.diag.Trace;
import com.pegasuscorp.orbe.orion.prompt.OrionMode;
import com.pegasuscorp.orbe.tools.orion.OrionCodeTool;
import com.pegasuscorp.orbe.tools.ToolCallback;
import com.pegasuscorp.orbe.tools.ToolRegistry;
import com.pegasuscorp.orbe.tools.ToolResult;
import com.pegasuscorp.orbe.tools.ToolTag;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class OrionCodeToolTest {

    private Context ctx;
    private AtomicInteger generateCalls;

    @Before
    public void setUp() {
        ctx = RuntimeEnvironment.getApplication();
        OrionStateStore.resetForTests();
        OrionStateStore.get().attach(ctx);
        Trace.init(ctx);
        Trace.clear(ctx);
        ContextualFileStore.resetInstanceForTests();
        generateCalls = new AtomicInteger(0);
        OrionOllamaClient.setTransportForTests((url, token, prompt, cb) -> {
            generateCalls.incrementAndGet();
            cb.onToken("fun ");
            cb.onToken("main()");
            cb.onComplete("fun main()");
        });
        OrionOllamaClient.setEnsureTransportForTests((url, token, progress) -> true);
        ApiKeyStore.setOrionToken(ctx, "tok_test");
    }

    @After
    public void tearDown() {
        OrionOllamaClient.setTransportForTests(null);
        OrionOllamaClient.setEnsureTransportForTests(null);
        OrionStateStore.resetForTests();
        ContextualFileStore.resetInstanceForTests();
    }

    @Test
    public void offline_returnsHonestMessage_noHttp() throws Exception {
        assertEquals(OrionStatus.OFFLINE, OrionStateStore.get().getStatus());
        AtomicReference<ToolResult> result = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        new OrionCodeTool().execute(ctx,
                new JSONObject().put("prompt", "écris hello world"),
                capturing(result, latch, null));
        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertNotNull(result.get());
        assertTrue(result.get().text.toLowerCase().contains("hors ligne")
                || result.get().text.toLowerCase().contains("lance"));
        assertEquals(0, generateCalls.get());
    }

    @Test
    public void stream_tokensThenDone() throws Exception {
        List<String> tokens = new ArrayList<>();
        AtomicReference<String> full = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        OrionOllamaClient.setTransportForTests((url, token, prompt, cb) -> {
            try {
                OrionOllamaClient.ParsedChunk a = OrionOllamaClient.parseStreamLine(
                        "{\"response\":\"Hel\",\"done\":false}");
                OrionOllamaClient.ParsedChunk b = OrionOllamaClient.parseStreamLine(
                        "{\"response\":\"lo\",\"done\":false}");
                OrionOllamaClient.ParsedChunk c = OrionOllamaClient.parseStreamLine(
                        "{\"response\":\"\",\"done\":true}");
                assertEquals("Hel", a.token);
                assertFalse(a.done);
                assertEquals("lo", b.token);
                assertTrue(c.done);
                cb.onToken(a.token);
                cb.onToken(b.token);
                cb.onComplete(a.token + b.token);
            } catch (Exception e) {
                cb.onError(e.getMessage());
            }
        });

        markReady();
        AtomicReference<ToolResult> result = new AtomicReference<>();
        new OrionCodeTool().execute(ctx, new JSONObject().put("prompt", "say hello"),
                new ToolCallback() {
                    @Override public void onSuccess(ToolResult r) {
                        result.set(r);
                        full.set(r.llmContext);
                        latch.countDown();
                    }
                    @Override public void onConfirmNeeded(String q, Runnable ok, Runnable no) {}
                    @Override public void onProgress(String message) {
                        tokens.add(message);
                    }
                    @Override public void onError(String error) {
                        fail(error);
                        latch.countDown();
                    }
                });
        assertTrue(latch.await(3, TimeUnit.SECONDS));
        assertEquals(Arrays.asList("Hel", "lo"), tokens);
        assertEquals("Hello", full.get());
        assertEquals("Hello", result.get().contextForSynthesis());
        assertTrue(result.get().text.contains("Orion"));
    }

    @Test
    public void buildPrompt_injectsLoadedContexts() {
        List<String> loaded = Collections.singletonList(
                "### Orion (orion-context.md)\nVision copilote");
        // Mode explicite : les specs .md ne sont injectées qu'en FEATURE (garde-fou
        // anti-dérive). Le défaut, mode null, vaut PATCH — donc pas d'injection.
        OrionPromptBuilder.BuiltPrompt built = OrionPromptBuilder.assemble(
                loaded, Collections.emptyList(), "", "écris un parser",
                null, null, null, null, OrionMode.FEATURE);
        assertTrue(built.prompt.contains("=== Documents .md chargés ==="));
        assertTrue(built.prompt.contains("Vision copilote"));
        // L'en-tête s'est enrichi en « === Demande (à satisfaire maintenant) === » :
        // on vérifie la section, pas son libellé exact.
        assertTrue(built.prompt.contains("=== Demande"));
        assertTrue(built.prompt.contains("écris un parser"));
        assertEquals(0, built.contextChunksUsed);
    }

    @Test
    public void buildPrompt_injectsRagChunksAboveThreshold() {
        List<ContextSearchIndex.Hit> hits = Arrays.asList(
                new ContextSearchIndex.Hit("orion-context.md", "Orion",
                        "Token Bearer pour Ollama", 0.82f),
                new ContextSearchIndex.Hit("x.md", "X", "ignore basse", 0.50f));
        // assemble ne refiltre pas — le seuil est appliqué à l'appel search()
        List<ContextSearchIndex.Hit> filtered = new ArrayList<>();
        for (ContextSearchIndex.Hit h : hits) {
            if (h.score >= OrionPromptBuilder.RAG_MIN_SCORE) filtered.add(h);
        }
        OrionPromptBuilder.BuiltPrompt built = OrionPromptBuilder.assemble(
                Collections.emptyList(), filtered, "extra tip", "auth ollama");
        assertTrue(built.prompt.contains("=== Contexte pertinent ==="));
        assertTrue(built.prompt.contains("Token Bearer"));
        assertFalse(built.prompt.contains("ignore basse"));
        assertTrue(built.prompt.contains("=== Document joint à ce message ==="));
        assertTrue(built.prompt.contains("extra tip"));
        assertEquals(1, built.contextChunksUsed);
    }

    @Test
    public void success_pingsActivityAndLogsTrace() throws Exception {
        markReady();
        OrionStateStore store = OrionStateStore.get();
        AtomicBoolean pingedViaReady = new AtomicBoolean(store.getStatus() == OrionStatus.READY);

        AtomicReference<ToolResult> result = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        new OrionCodeTool().execute(ctx,
                new JSONObject().put("prompt", "génère une fonction"),
                capturing(result, latch, null));
        assertTrue(latch.await(3, TimeUnit.SECONDS));
        assertNotNull(result.get());
        assertEquals(1, generateCalls.get());
        assertEquals(OrionStatus.READY, store.getStatus());
        assertTrue(pingedViaReady.get() || store.getStatus() == OrionStatus.READY);

        Trace.flushForTests();
        String jsonl = readTrace();
        assertTrue("orion_call manquant dans trace: " + jsonl, jsonl.contains("orion_call"));
        assertTrue(jsonl.contains("prompt_chars"));
        assertTrue(jsonl.contains("response_chars"));
        assertTrue(jsonl.contains("wall_ms"));
        assertTrue(jsonl.contains("context_chunks"));
    }

    @Test
    public void emptyPrompt_errors() throws Exception {
        markReady();
        AtomicReference<String> err = new AtomicReference<>();
        new OrionCodeTool().execute(ctx, new JSONObject().put("prompt", "  "),
                new ToolCallback() {
                    @Override public void onSuccess(ToolResult result) {
                        fail("should error");
                    }
                    @Override public void onConfirmNeeded(String q, Runnable ok, Runnable no) {}
                    @Override public void onError(String error) {
                        err.set(error);
                    }
                });
        assertNotNull(err.get());
        assertTrue(err.get().toLowerCase().contains("orion"));
        assertEquals(0, generateCalls.get());
    }

    @Test
    public void registry_hasOrionCode() {
        ToolRegistry reg = new ToolRegistry();
        assertNotNull(reg.findById("orion_code"));
        assertEquals(ToolTag.ORION_CODE, reg.findById("orion_code").tag());
    }

    @Test
    public void getLoadedContexts_readsLoadedFile() throws Exception {
        ContextualFileStore store = ContextualFileStore.getInstance(ctx);
        store.save("orion", "# Test Orion\nStack Android");
        store.load("orion");
        List<String> loaded = store.getLoadedContexts();
        assertFalse(loaded.isEmpty());
        assertTrue(loaded.get(0).contains("Stack Android"));
    }

    private void markReady() {
        GpuOffer offer = new GpuOffer("gpu", "RTX 3090", 24, 0.29f, true);
        OrionStateStore store = OrionStateStore.get();
        store.markStarting("pod123", offer);
        store.setOllamaUrl(OrionStateStore.buildOllamaUrl("pod123"));
        store.markReady();
    }

    private static ToolCallback capturing(AtomicReference<ToolResult> result,
            CountDownLatch latch, AtomicReference<String> err) {
        return new ToolCallback() {
            @Override public void onSuccess(ToolResult r) {
                result.set(r);
                latch.countDown();
            }
            @Override public void onConfirmNeeded(String q, Runnable ok, Runnable no) {}
            @Override public void onError(String error) {
                if (err != null) err.set(error);
                else fail(error);
                latch.countDown();
            }
        };
    }

    private String readTrace() throws Exception {
        File f = Trace.file();
        assertNotNull(f);
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString();
    }
}
