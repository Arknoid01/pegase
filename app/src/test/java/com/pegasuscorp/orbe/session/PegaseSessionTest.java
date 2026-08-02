package com.pegasuscorp.orbe.session;

import android.content.Context;

import com.pegasuscorp.orbe.bureau.BureauMarkdownBrain;
import com.pegasuscorp.orbe.bureau.BureauMarkdownParser;
import com.pegasuscorp.orbe.chat.ConversationManager;
import com.pegasuscorp.orbe.chat.FakeChatBackend;
import com.pegasuscorp.orbe.diag.Trace;
import com.pegasuscorp.orbe.memory.FakeMemoryStore;
import com.pegasuscorp.orbe.tools.Tool;
import com.pegasuscorp.orbe.tools.ToolCallback;
import com.pegasuscorp.orbe.tools.ToolRegistry;
import com.pegasuscorp.orbe.tools.ToolResult;
import com.pegasuscorp.orbe.tools.ToolTag;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowLooper;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class PegaseSessionTest {

    private Context ctx;
    private FakeChatBackend backend;
    private FakeMemoryStore memory;
    private ConversationManager conversation;
    private PegaseSession session;

    @Before
    public void setUp() {
        ctx = RuntimeEnvironment.getApplication();
        Trace.init(ctx);
        backend = new FakeChatBackend();
        memory = new FakeMemoryStore();
        conversation = new ConversationManager(backend, memory);
        session = new PegaseSession(ctx, conversation, new StubToolRegistry());
        session.init(new SessionContext(Channel.TEXT, false));
    }

    private static void drainMainThread() {
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks();
    }

    @Test
    public void send_plainText_notifiesOnReply() {
        backend.nextReply = "Salut !";
        AtomicReference<String> reply = new AtomicReference<>();
        AtomicReference<Boolean> toolFired = new AtomicReference<>();

        session.send("Bonjour", new SessionObserver() {
            @Override
            public void onReply(String text, boolean fired) {
                reply.set(text);
                toolFired.set(fired);
            }

            @Override
            public void onToolResult(ToolResult result) {}

            @Override
            public void onError(String message) {
                fail(message);
            }
        });

        drainMainThread();
        assertEquals("Salut !", reply.get());
        assertFalse(toolFired.get());
        assertFalse(conversation.historySnapshot().get(conversation.historySnapshot().size() - 1).fromUser);
    }

    @Test
    public void init_defersChannelChange_whileTurnInFlight() {
        session.init(new SessionContext(Channel.VOICE, false));
        backend.deferSend = true;
        backend.nextReply = "ok voix";

        session.send("Dis bonjour", new SessionObserver() {
            @Override
            public void onReply(String text, boolean fired) {}

            @Override
            public void onToolResult(ToolResult result) {}

            @Override
            public void onError(String message) {
                fail(message);
            }
        });

        assertEquals(Channel.VOICE, session.getChannel());
        session.init(new SessionContext(Channel.COPILOT, false));
        // Canal du tour courant inchangé ; COPILOT en attente.
        assertEquals(Channel.VOICE, session.getChannel());

        backend.flushDeferredSend();
        drainMainThread();
        assertEquals(Channel.COPILOT, session.getChannel());
    }

    @Test
    public void send_toolJson_dispatchesOnToolResult() {
        backend.nextReply = "{\"tool\":\"stub\",\"params\":{}}";
        AtomicReference<ToolResult> toolOut = new AtomicReference<>();

        session.send("Fais stub", new SessionObserver() {
            @Override
            public void onReply(String text, boolean fired) {
                assertTrue(fired);
            }

            @Override
            public void onToolResult(ToolResult result) {
                toolOut.set(result);
            }

            @Override
            public void onError(String message) {
                fail(message);
            }
        });

        drainMainThread();
        assertNotNull(toolOut.get());
        assertEquals("stub ok", toolOut.get().text);
        assertEquals("stub ok", conversation.historySnapshot().get(1).text);
    }

    @Test
    public void executeTool_withoutLlm_recordsHistory() {
        AtomicReference<ToolResult> toolOut = new AtomicReference<>();

        session.executeTool("stub", new JSONObject(), new SessionObserver() {
            @Override
            public void onReply(String text, boolean fired) {}

            @Override
            public void onToolResult(ToolResult result) {
                toolOut.set(result);
            }

            @Override
            public void onError(String message) {
                fail(message);
            }
        });

        drainMainThread();
        assertEquals("stub ok", toolOut.get().text);
        assertEquals(1, conversation.historySnapshot().size());
        assertEquals("stub ok", conversation.historySnapshot().get(0).text);
    }

    @Test
    public void executeTool_imageUrl_deliversTypedResult() {
        AtomicReference<ToolResult> toolOut = new AtomicReference<>();

        session.executeTool("stub_image", new JSONObject(), new SessionObserver() {
            @Override
            public void onReply(String text, boolean fired) {}

            @Override
            public void onToolResult(ToolResult result) {
                toolOut.set(result);
            }

            @Override
            public void onError(String message) {
                fail(message);
            }
        });

        drainMainThread();
        assertNotNull(toolOut.get());
        assertEquals(ToolResult.Kind.IMAGE_URL, toolOut.get().kind);
        assertEquals("https://example.test/apod.jpg", toolOut.get().imageUrl);
        assertTrue(toolOut.get().wireText().startsWith("NASA_IMAGE:"));
    }

    @Test
    public void voice_withNativeFc_disablesStreaming() {
        backend.streaming = true;
        backend.nextReply = "Bonjour voix !";
        session.init(new SessionContext(Channel.VOICE, true));

        AtomicReference<String> reply = new AtomicReference<>();
        AtomicReference<String> partial = new AtomicReference<>();

        session.send("Bonjour voix", new SessionObserver() {
            @Override
            public void onReply(String text, boolean fired) {
                reply.set(text);
            }

            @Override
            public void onPartial(String accumulated) {
                partial.set(accumulated);
            }

            @Override
            public void onToolResult(ToolResult result) {}

            @Override
            public void onError(String message) {
                fail(message);
            }
        });

        drainMainThread();
        assertTrue(backend.lastOptions.nativeTools);
        assertNull(partial.get());
        assertEquals("Bonjour voix !", reply.get());
    }

    @Test
    public void voice_executeToolFromJson_recordsUserLine() {
        session.init(new SessionContext(Channel.VOICE, true));
        AtomicReference<ToolResult> toolOut = new AtomicReference<>();

        boolean handled = session.executeToolFromJson(
                "{\"tool\":\"stub\",\"params\":{}}",
                "Allume la lumière",
                new SessionObserver() {
                    @Override
                    public void onReply(String text, boolean fired) {}

                    @Override
                    public void onToolResult(ToolResult result) {
                        toolOut.set(result);
                    }

                    @Override
                    public void onError(String message) {
                        fail(message);
                    }
                });

        drainMainThread();
        assertTrue(handled);
        assertEquals("stub ok", toolOut.get().text);
        assertEquals(2, conversation.historySnapshot().size());
        assertTrue(conversation.historySnapshot().get(0).fromUser);
        assertEquals("Allume la lumière", conversation.historySnapshot().get(0).text);
        assertEquals("stub ok", conversation.historySnapshot().get(1).text);
    }

    @Test
    public void voice_llmToolBlocked_skipsToolDispatch() {
        session.init(new SessionContext(Channel.VOICE, true));
        backend.nextReply = "{\"tool\":\"stub\",\"params\":{}}";
        AtomicReference<Boolean> blocked = new AtomicReference<>(false);
        AtomicReference<ToolResult> toolOut = new AtomicReference<>();

        session.send("Lance stub", new SessionObserver() {
            @Override
            public void onReply(String text, boolean fired) {
                assertTrue(fired);
            }

            @Override
            public void onToolResult(ToolResult result) {
                toolOut.set(result);
            }

            @Override
            public void onToolBlocked() {
                blocked.set(true);
            }

            @Override
            public boolean allowToolExecution() {
                return false;
            }

            @Override
            public void onError(String message) {
                fail(message);
            }
        });

        drainMainThread();
        assertTrue(blocked.get());
        assertNull(toolOut.get());
    }

    @Test
    public void text_nativeToolCall_runsAgenticSynthesis() throws Exception {
        backend.nextNativeToolCalls = java.util.Collections.singletonList(
                new com.pegasuscorp.orbe.chat.NativeToolCall(
                        "call_1", "stub", new JSONObject()));
        backend.nextSynthesisReply = "C'est bon, stub exécuté.";
        session.init(new SessionContext(Channel.TEXT, false));
        AtomicReference<String> finalReply = new AtomicReference<>();

        session.send("Lance stub", new SessionObserver() {
            @Override
            public void onReply(String text, boolean fired) {
                if (!fired) {
                    finalReply.set(text);
                }
            }

            @Override
            public void onToolResult(ToolResult result) {
                fail("Pas de onToolResult direct en mode agentique TEXT");
            }

            @Override
            public void onError(String message) {
                fail(message);
            }
        });

        drainMainThread();
        assertTrue(backend.lastOptions.nativeTools);
        assertEquals(1, backend.agenticSendCount);
        assertNotNull(backend.lastAgenticChain);
        assertEquals("stub ok", backend.lastAgenticChain.lastToolResult());
        assertEquals("C'est bon, stub exécuté.", finalReply.get());
        String lastAssistant = lastNonSystemAssistant(conversation.historySnapshot());
        assertEquals("C'est bon, stub exécuté.", lastAssistant);
    }

    private static String lastNonSystemAssistant(java.util.List<com.pegasuscorp.orbe.chat.ChatBackend.Turn> turns) {
        for (int i = turns.size() - 1; i >= 0; i--) {
            com.pegasuscorp.orbe.chat.ChatBackend.Turn t = turns.get(i);
            if (!t.fromUser && !t.system) return t.text;
        }
        return null;
    }

    @Test
    public void voice_nativeToolCall_runsAgenticSynthesis() throws Exception {
        backend.nextNativeToolCalls = java.util.Collections.singletonList(
                new com.pegasuscorp.orbe.chat.NativeToolCall(
                        "call_1", "stub", new JSONObject()));
        backend.nextSynthesisReply = "C'est fait pour la voix.";
        session.init(new SessionContext(Channel.VOICE, true));
        AtomicReference<String> finalReply = new AtomicReference<>();

        session.send("Lance stub", new SessionObserver() {
            @Override
            public void onReply(String text, boolean fired) {
                if (!fired) finalReply.set(text);
            }

            @Override
            public void onToolResult(ToolResult result) {
                fail("Pas de onToolResult direct en agentique voix");
            }

            @Override
            public void onError(String message) {
                fail(message);
            }
        });

        drainMainThread();
        assertTrue(backend.lastOptions.nativeTools);
        assertEquals("C'est fait pour la voix.", finalReply.get());
    }

    @Test
    public void text_multiHop_twoToolsThenSynthesis() throws Exception {
        backend.nextNativeToolCalls = java.util.Collections.singletonList(
                new com.pegasuscorp.orbe.chat.NativeToolCall(
                        "call_1", "stub", new JSONObject()));
        backend.nextAgenticToolCalls = java.util.Collections.singletonList(
                new com.pegasuscorp.orbe.chat.NativeToolCall(
                        "call_2", "stub2", new JSONObject()));
        backend.nextSynthesisReply = "Les deux stubs sont faits.";
        session.init(new SessionContext(Channel.TEXT, false));
        AtomicReference<String> finalReply = new AtomicReference<>();
        AtomicInteger toolHops = new AtomicInteger();

        session.send("Double stub", new SessionObserver() {
            @Override
            public void onReply(String text, boolean fired) {
                if (fired) toolHops.incrementAndGet();
                else finalReply.set(text);
            }

            @Override
            public void onToolResult(ToolResult result) {}

            @Override
            public void onError(String message) {
                fail(message);
            }
        });

        drainMainThread();
        assertEquals(2, backend.agenticSendCount);
        assertEquals(2, toolHops.get());
        assertEquals(2, backend.lastAgenticChain.steps().size());
        assertEquals("Les deux stubs sont faits.", finalReply.get());
    }

    @Test
    public void text_searchBlocksSecondSearchInSameTurn() throws Exception {
        JSONObject searchArgs = new JSONObject().put("query", "coupe du monde ce soir");
        backend.nextNativeToolCalls = java.util.Collections.singletonList(
                new com.pegasuscorp.orbe.chat.NativeToolCall("call_1", "search", searchArgs));
        backend.nextAgenticToolCalls = java.util.Collections.singletonList(
                new com.pegasuscorp.orbe.chat.NativeToolCall("call_2", "search",
                        new JSONObject(searchArgs.toString())));
        backend.nextSynthesisReply = "Demi-finale ce soir à Lyon.";
        session.init(new SessionContext(Channel.TEXT, false));
        AtomicReference<String> finalReply = new AtomicReference<>();

        session.send("Match coupe du monde ce soir", new SessionObserver() {
            @Override
            public void onReply(String text, boolean fired) {
                if (!fired) finalReply.set(text);
            }

            @Override
            public void onToolResult(ToolResult result) {}

            @Override
            public void onError(String message) {
                fail(message);
            }
        });

        drainMainThread();
        assertFalse(backend.lastAgenticOptions.allowMoreTools);
        assertEquals(2, backend.agenticSendCount);
        assertEquals("Demi-finale ce soir à Lyon.", finalReply.get());
    }

    @Test
    public void text_mathSignsBypassLlmAndUseCalculator() {
        backend.nextSynthesisReply = "Douze fois quatre, ça fait quarante-huit.";
        AtomicReference<String> finalReply = new AtomicReference<>();
        AtomicInteger toolFires = new AtomicInteger();

        session.send("12×4", new SessionObserver() {
            @Override
            public void onReply(String text, boolean fired) {
                if (fired) toolFires.incrementAndGet();
                else finalReply.set(text);
            }

            @Override
            public void onToolResult(ToolResult result) {
                finalReply.compareAndSet(null, result.text);
            }

            @Override
            public void onError(String message) {
                fail(message);
            }
        });

        drainMainThread();
        assertEquals(0, backend.sendCount);
        String out = finalReply.get();
        assertNotNull(out);
        assertTrue(out.contains("48") || out.toLowerCase().contains("quarante"));
    }

    @Test
    public void text_mathSigns_doNotStartAgenticSynthesisAfterCalculator() {
        AtomicReference<String> finalReply = new AtomicReference<>();

        session.send("119*5,5/100", new SessionObserver() {
            @Override
            public void onReply(String text, boolean fired) {
                if (!fired) finalReply.set(text);
            }

            @Override
            public void onToolResult(ToolResult result) {
                fail("Le calcul déterministe doit répondre directement sans onToolResult.");
            }

            @Override
            public void onError(String message) {
                fail(message);
            }
        });

        drainMainThread();
        assertEquals(0, backend.sendCount);
        assertEquals(0, backend.agenticSendCount);
        assertNotNull(finalReply.get());
        assertTrue(finalReply.get().contains("6,545"));
        assertEquals(2, conversation.historySnapshot().size());
        assertEquals("119*5,5/100", conversation.historySnapshot().get(0).text);
    }

    @Test
    public void editBureauMarkdown_parsesReplyWithoutHistoryPollution() {
        session.init(new SessionContext(Channel.BUREAU, false));
        conversation.enter();
        awaitReply(conversation, "Tour chat avant bureau");
        int historyBefore = conversation.historySnapshot().size();

        backend.nextReply = "> C'est noté.\n## Dev Pégase\n- [ ] Morning Routine\n";
        AtomicReference<BureauMarkdownBrain.Result> result = new AtomicReference<>();

        session.editBureauMarkdown("# Notes\n", "Ajoute une section Dev", new BureauMarkdownBrain.Callback() {
            @Override
            public void onResult(BureauMarkdownBrain.Result r) {
                result.set(r);
            }

            @Override
            public void onError(String message) {
                fail(message);
            }
        });

        drainMainThread();
        assertNotNull(result.get());
        BureauMarkdownParser.Parsed parsed = result.get().parsed;
        assertEquals("C'est noté.", parsed.speak);
        assertTrue(parsed.markdown.contains("## Dev Pégase"));
        assertEquals(historyBefore, conversation.historySnapshot().size());
        assertEquals(Channel.BUREAU, session.getChannel());
    }

    @Test
    public void editBureauMarkdown_llmError_usesLocalFallback() {
        session.init(new SessionContext(Channel.BUREAU, false));
        backend.nextError = "HTTP 503";
        AtomicReference<BureauMarkdownBrain.Result> result = new AtomicReference<>();

        session.editBureauMarkdown("", "nouvelle section Dev", new BureauMarkdownBrain.Callback() {
            @Override
            public void onResult(BureauMarkdownBrain.Result r) {
                result.set(r);
            }

            @Override
            public void onError(String message) {
                fail(message);
            }
        });

        drainMainThread();
        assertNotNull(result.get());
        assertTrue(result.get().parsed.markdown.contains("## Dev"));
        assertEquals("Section ajoutée en local.", result.get().parsed.speak);
    }

    @Test
    public void editBureauMarkdown_calculatorToolCall_writesResultNotJson() {
        session.init(new SessionContext(Channel.BUREAU, false));
        backend.nextReply = "{\"tool\":\"calculator\",\"params\":{\"expression\":\"12*4\"}}";
        AtomicReference<BureauMarkdownBrain.Result> result = new AtomicReference<>();

        session.editBureauMarkdown("# Notes\n", "Calcule 12 fois 4",
                new BureauMarkdownBrain.Callback() {
                    @Override
                    public void onResult(BureauMarkdownBrain.Result r) {
                        result.set(r);
                    }

                    @Override
                    public void onError(String message) {
                        fail(message);
                    }
                });

        drainMainThread();
        assertNotNull(result.get());
        String md = result.get().parsed.markdown;
        String speak = result.get().parsed.speak;
        String raw = result.get().raw;
        assertFalse("JSON brut ne doit pas rester dans le .md",
                md.contains("\"tool\"") || md.contains("calculator"));
        assertFalse(raw.contains("\"tool\":\"calculator\""));
        assertTrue("Résultat attendu dans md ou speak : " + md + " / " + speak,
                md.contains("48") || speak.contains("48")
                        || md.toLowerCase().contains("quarante")
                        || speak.toLowerCase().contains("quarante"));
    }

    @Test
    public void editBureauMarkdown_toolCallWithPreamble_keepsSpeakAndReplacesJson() {
        session.init(new SessionContext(Channel.BUREAU, false));
        backend.nextReply = "> Voilà le calcul.\n"
                + "{\"tool\":\"calculator\",\"params\":{\"expression\":\"10+5\"}}\n";
        AtomicReference<BureauMarkdownBrain.Result> result = new AtomicReference<>();

        session.editBureauMarkdown("", "10+5", new BureauMarkdownBrain.Callback() {
            @Override
            public void onResult(BureauMarkdownBrain.Result r) {
                result.set(r);
            }

            @Override
            public void onError(String message) {
                fail(message);
            }
        });

        drainMainThread();
        assertNotNull(result.get());
        assertTrue(result.get().parsed.speak.contains("Voilà")
                || result.get().parsed.speak.contains("Voila"));
        assertFalse(result.get().parsed.markdown.contains("\"tool\""));
        assertTrue(result.get().parsed.markdown.contains("15")
                || result.get().parsed.speak.contains("15"));
    }

    @Test
    public void completeBureauSync_returnsJsonWithoutHistory() throws Exception {
        session.init(new SessionContext(Channel.BUREAU, false));
        conversation.enter();
        awaitReply(conversation, "Avant canvas");
        backend.nextReply = "{\"speak\":\"Ok\",\"lines\":[\"note\"],\"boxes\":[]}";

        String out = session.completeBureauSync("Prompt canvas");

        assertTrue(out.contains("\"speak\""));
        assertEquals(2, conversation.historySnapshot().size());
        assertTrue(backend.lastHistory.isEmpty());
    }

    @Test
    public void containsDiagFallbackKeywords_detectsDiagVocab() {
        assertTrue(PegaseSession.containsDiagFallbackKeywords("Bug de synchronisation"));
        assertTrue(PegaseSession.containsDiagFallbackKeywords("Problème de notification"));
        assertTrue(PegaseSession.containsDiagFallbackKeywords("une erreur de plantage"));
        assertFalse(PegaseSession.containsDiagFallbackKeywords("Acheter du pain"));
    }

    @Test
    public void notepadAdd_onFallbackWithDiagWords_isBlocked() throws Exception {
        com.pegasuscorp.orbe.chat.FallbackChatBackend.setOnFallbackBackendForTests(true);
        try {
            JSONObject params = new JSONObject()
                    .put("action", "add")
                    .put("text", "Bug de synchronisation des notifications");
            assertTrue(PegaseSession.shouldBlockNotepadDiagFallback(ctx, "notepad", params));

            AtomicReference<ToolResult> toolOut = new AtomicReference<>();
            AtomicInteger adds = new AtomicInteger();
            session = new PegaseSession(ctx, conversation, new ToolRegistry() {
                @Override
                public Tool findById(String id) {
                    if ("notepad".equals(id)) {
                        return new Tool() {
                            @Override public String id() { return "notepad"; }
                            @Override public ToolTag tag() { return ToolTag.NOTEPAD; }
                            @Override public String description() { return "notepad"; }
                            @Override
                            public void execute(Context c, JSONObject p, ToolCallback cb) {
                                adds.incrementAndGet();
                                cb.onSuccess(ToolResult.text("should not run"));
                            }
                        };
                    }
                    return super.findById(id);
                }
            });
            session.init(new SessionContext(Channel.TEXT, false));

            session.executeTool("notepad", params, "Note le bug", new SessionObserver() {
                @Override public void onReply(String text, boolean fired) {}
                @Override
                public void onToolResult(ToolResult result) {
                    toolOut.set(result);
                }
                @Override public void onError(String message) { fail(message); }
            });
            drainMainThread();

            assertEquals(0, adds.get());
            assertNotNull(toolOut.get());
            assertTrue(toolOut.get().text.toLowerCase().contains("je ne note pas"));
            Trace.flushForTests();
            String jsonl = new String(
                    java.nio.file.Files.readAllBytes(Trace.file().toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(jsonl.contains("diag_fallback_blocked"));
        } finally {
            com.pegasuscorp.orbe.chat.FallbackChatBackend.setOnFallbackBackendForTests(null);
        }
    }

    private static void awaitReply(ConversationManager conversation, String message) {
        AtomicReference<String> reply = new AtomicReference<>();
        conversation.send(message, new com.pegasuscorp.orbe.chat.ChatBackend.OnReply() {
            @Override
            public void onReply(String text) {
                reply.set(text);
            }

            @Override
            public void onError(String error) {
                fail(error);
            }
        });
        assertNotNull(reply.get());
    }

    /** Registry de test avec outils stub — évite le réseau. */
    private static final class         StubToolRegistry extends ToolRegistry {
        @Override
        public Tool findById(String id) {
            if ("stub".equals(id)) return new StubTool(false, "stub");
            if ("stub2".equals(id)) return new StubTool(false, "stub2");
            if ("stub_image".equals(id)) return new StubTool(true, "stub_image");
            if ("search".equals(id)) return new StubTool(false, "search");
            return super.findById(id);
        }
    }

    private static final class StubTool implements Tool {
        private final boolean image;
        private final String toolId;

        StubTool(boolean image, String toolId) {
            this.image = image;
            this.toolId = toolId;
        }

        @Override
        public String id() {
            return toolId;
        }

        @Override
        public ToolTag tag() {
            return ToolTag.DEVICE;
        }

        @Override
        public String description() {
            return toolId + "() — test";
        }

        @Override
        public void execute(Context ctx, JSONObject params, ToolCallback cb) {
            if (image) {
                cb.onSuccess(ToolResult.imageUrl("APOD caption", "https://example.test/apod.jpg"));
            } else if ("search".equals(toolId)) {
                cb.onSuccess(ToolResult.text("Résultat web synthétisé pour test."));
            } else {
                cb.onSuccess(ToolResult.text(toolId + " ok"));
            }
        }
    }
}
