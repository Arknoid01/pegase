package com.pegasuscorp.orbe.chat;

import com.pegasuscorp.orbe.diag.Trace;
import com.pegasuscorp.orbe.memory.FakeMemoryStore;
import com.pegasuscorp.orbe.tools.ToolResult;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * Filet de sécurité PegaseSession — étape 0.
 * Aucun réseau : FakeMemoryStore + FakeChatBackend.
 */
@RunWith(RobolectricTestRunner.class)
public class ConversationManagerTest {

    private FakeMemoryStore memory;
    private FakeChatBackend backend;
    private ConversationManager conversation;

    @Before
    public void setUp() {
        Trace.init(RuntimeEnvironment.getApplication());
        memory = new FakeMemoryStore();
        backend = new FakeChatBackend();
        conversation = new ConversationManager(backend, memory);
    }

    @Test
    public void normalExchange_historyEndsWithAssistant() {
        conversation.enter();
        awaitReply(conversation, "Salut Pégase");

        List<ChatBackend.Turn> history = conversation.historySnapshot();
        assertEquals(2, history.size());
        assertTrue(history.get(0).fromUser);
        assertEquals("Salut Pégase", history.get(0).text);
        assertFalse(history.get(1).fromUser);
        assertFalse(conversation.isUserTurnPending());
        assertFalse(lastTurnIsUser(history));
    }

    @Test
    public void twoCallsInRow_noDuplicateUserTurn() {
        conversation.enter();
        awaitReply(conversation, "Premier message");
        awaitReply(conversation, "Deuxième message");

        List<ChatBackend.Turn> history = conversation.historySnapshot();
        assertEquals(4, history.size());
        assertEquals("Premier message", history.get(0).text);
        assertEquals("Deuxième message", history.get(2).text);
        assertFalse(lastTurnIsUser(history));
        assertEquals(2, backend.sendCount);
    }

    @Test
    public void backendError_recordsAssistantAndClearsPending() {
        conversation.enter();
        backend.nextError = "HTTP 503";

        AtomicReference<String> err = new AtomicReference<>();
        conversation.send("Test", new ChatBackend.OnReply() {
            @Override
            public void onReply(String text) {
                fail("Expected error");
            }

            @Override
            public void onError(String error) {
                err.set(error);
            }
        });

        assertEquals("Le service cloud est indisponible pour l'instant. Réessaie dans une minute.",
                err.get());
        assertFalse(conversation.isUserTurnPending());
        List<ChatBackend.Turn> history = conversation.historySnapshot();
        assertEquals(2, history.size());
        assertFalse(history.get(1).fromUser);
        assertFalse(lastTurnIsUser(history));
        assertFalse(history.get(1).text.toLowerCase().contains("groq"));
    }

    @Test
    public void rateLimitError_doesNotStoreGroqQuotaInHistory() {
        conversation.enter();
        backend.nextError = "Rate limit Groq — réessai dans 1884000 ms";

        AtomicReference<String> err = new AtomicReference<>();
        conversation.send("Test", new ChatBackend.OnReply() {
            @Override
            public void onReply(String text) {
                fail("Expected error");
            }

            @Override
            public void onError(String error) {
                err.set(error);
            }
        });

        assertTrue(ChatSpokenErrors.isRateLimit(err.get())
                || err.get().contains("saturé")
                || err.get().contains("minute"));
        List<ChatBackend.Turn> history = conversation.historySnapshot();
        assertEquals(1, history.size());
        assertTrue(history.get(0).fromUser);
        assertEquals("Test", history.get(0).text);
        assertFalse(conversation.isUserTurnPending());
    }

    @Test
    public void toolCallWithoutPreamble_doesNotLeavePendingUser() {
        conversation.enter();
        backend.nextReply = "{\"tool\":\"weather\",\"params\":{\"days\":1}}";

        AtomicReference<String> reply = new AtomicReference<>();
        conversation.send("Quel temps ?", new ChatBackend.OnReply() {
            @Override
            public void onReply(String text) {
                reply.set(text);
            }

            @Override
            public void onError(String error) {
                fail(error);
            }
        });

        assertTrue(reply.get().contains("\"tool\""));
        // Pas de tour assistant ajouté tant que l'outil n'a pas répondu.
        assertTrue(conversation.isUserTurnPending());
        assertEquals(1, conversation.historySnapshot().size());

        conversation.recordToolReply("Il fait 18 degrés.");
        assertFalse(conversation.isUserTurnPending());
        List<ChatBackend.Turn> history = conversation.historySnapshot();
        assertEquals(2, history.size());
        assertEquals("Il fait 18 degrés.", history.get(1).text);
        assertFalse(lastTurnIsUser(history));
    }

    @Test
    public void enter_keepsTrailingUserAsPending() {
        memory.setRecentTurns(Arrays.asList(
                new ChatBackend.Turn(true, "vieux message"),
                new ChatBackend.Turn(false, "vieille réponse"),
                new ChatBackend.Turn(true, "tour orphelin")));

        conversation.enter();

        List<ChatBackend.Turn> history = conversation.historySnapshot();
        assertEquals(3, history.size());
        assertTrue(lastTurnIsUser(history));
        assertEquals("tour orphelin", history.get(2).text);
        assertTrue(conversation.isUserTurnPending());
    }

    @Test
    public void pendingUserResend_replacesLastUserTurn() {
        conversation.enter();
        backend.nextReply = "{\"tool\":\"weather\",\"params\":{\"days\":1}}";

        conversation.send("Première demande", new ChatBackend.OnReply() {
            @Override public void onReply(String text) { }
            @Override public void onError(String error) { fail(error); }
        });
        assertTrue(conversation.isUserTurnPending());
        assertEquals(1, conversation.historySnapshot().size());
        assertEquals("Première demande", conversation.historySnapshot().get(0).text);

        backend.nextReply = "Réponse deux.";
        awaitReply(conversation, "Deuxième demande");

        List<ChatBackend.Turn> history = conversation.historySnapshot();
        assertEquals(2, history.size());
        assertEquals("Deuxième demande", history.get(0).text);
        assertEquals("Réponse deux.", history.get(1).text);
        assertFalse(lastTurnIsUser(history));
    }

    @Test
    public void assistantReply_stripsThinkingFromHistory() {
        conversation.enter();
        backend.nextReply = "<think>plan</think>Bonjour !";

        awaitReply(conversation, "Salut");

        String stored = conversation.historySnapshot().get(1).text;
        assertEquals("Bonjour !", stored);
    }

    @Test
    public void enter_normalizesPollutedStore() {
        memory.setRecentTurns(Arrays.asList(
                new ChatBackend.Turn(true, "a"),
                new ChatBackend.Turn(true, "b"),
                new ChatBackend.Turn(false,
                        "<think>x</think>réponse")));

        conversation.enter();

        List<ChatBackend.Turn> history = conversation.historySnapshot();
        assertEquals(2, history.size());
        assertEquals("b", history.get(0).text);
        assertEquals("réponse", history.get(1).text);
    }

    @Test
    public void exit_persistsHistoryWithoutTrailingUser() {
        conversation.enter();
        awaitReply(conversation, "On note ça");
        conversation.exit();

        List<ChatBackend.Turn> stored = memory.getRecentTurns();
        assertEquals(2, stored.size());
        assertFalse(lastTurnIsUser(stored));
    }

    @Test
    public void phantomAction_blockedWhenNoTool() {
        conversation.enter();
        backend.nextReply = "C'est noté dans ton bloc-notes.";

        AtomicReference<String> reply = new AtomicReference<>();
        conversation.send("Note achat du lait", new ChatBackend.OnReply() {
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
        assertTrue(reply.get().contains("aucune action"));
        List<ChatBackend.Turn> history = conversation.historySnapshot();
        assertEquals(2, history.size());
        assertTrue(history.get(1).text.contains("aucune action"));
    }

    @Test
    public void phantomAction_jaiNote_blockedEvenIfUserAmbiguous() {
        conversation.enter();
        backend.nextReply = "J'ai noté ça pour toi.";

        AtomicReference<String> reply = new AtomicReference<>();
        // Demande ambiguë : pas de verbe d'action ACTION_INTENTS clair
        conversation.send("il y a le buffet demain avec des courgettes", new ChatBackend.OnReply() {
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
        assertTrue("Scout/120B : « j'ai noté » sans notepad = fantôme",
                reply.get().contains("aucune action"));
    }

    @Test
    public void phantomAction_logsToolHesitationEvent() throws Exception {
        Trace.clear(RuntimeEnvironment.getApplication());
        conversation.enter();
        backend.nextReply = "C'est noté, j'ai ajouté ça à ta liste.";
        conversation.send("Note le lait pour demain", new ChatBackend.OnReply() {
            @Override public void onReply(String text) {}
            @Override public void onError(String error) { fail(error); }
        });
        Trace.flushForTests();

        String jsonl = readTraceFile();
        assertTrue(jsonl.contains("\"type\":\"tool_hesitation\"")
                || jsonl.contains("\"type\": \"tool_hesitation\""));
        assertTrue(jsonl.contains("notepad") || jsonl.contains("phantom_action"));
        assertTrue(jsonl.contains("lait") || jsonl.contains("user_msg"));
    }

    @Test
    public void afterMemoryToolSuccess_synthesisNotBlockedAsPhantom() {
        conversation.enter();
        conversation.addUserMessage("Souviens-toi que j'aime le café");
        // Simule memory tool OK puis synthèse agentique
        conversation.recordToolReply("Je retiens : j'aime le café");
        conversation.recordToolSuccessHint("memory", "Je retiens : j'aime le café");

        backend.nextSynthesisReply = "C'est noté, je retiens que tu aimes le café.";
        AtomicReference<String> reply = new AtomicReference<>();
        AgenticChain chain = new AgenticChain(conversation.historySnapshot(),
                conversation.getLastUserText());
        conversation.sendAgenticStep(chain, ChatSendOptions.agenticStep(
                ChatSendOptions.legacy().allowedTools, false), new ChatBackend.OnReply() {
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
        assertFalse("Après memory réussi, « C'est noté » ne doit pas être un fantôme",
                reply.get().contains("aucune action"));
        assertTrue(reply.get().toLowerCase().contains("café")
                || reply.get().toLowerCase().contains("note")
                || reply.get().toLowerCase().contains("retiens"));
    }

    private static String readTraceFile() throws Exception {
        java.io.File f = Trace.file();
        assertNotNull(f);
        assertTrue(f.exists());
        return new String(java.nio.file.Files.readAllBytes(f.toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    public void identicalMessagesTwice_bothRecorded() {
        conversation.enter();
        awaitReply(conversation, "Ping");
        awaitReply(conversation, "Ping");

        List<ChatBackend.Turn> history = conversation.historySnapshot();
        assertEquals(4, history.size());
        assertEquals("Ping", history.get(0).text);
        assertEquals("Ping", history.get(2).text);
    }

    @Test
    public void toolResult_text_wireTextIsPlain() {
        ToolResult result = ToolResult.text("Il fait beau.");
        assertEquals(ToolResult.Kind.TEXT, result.kind);
        assertEquals("Il fait beau.", result.wireText());
        assertEquals("Il fait beau.", result.text);
    }

    @Test
    public void toolResult_imageUrl_wireTextPreservesNasaPrefix() {
        String body = "NASA APOD du jour :\nTitre : Nebula";
        ToolResult result = ToolResult.imageUrl(body, "https://apod.nasa.gov/image.jpg");
        assertEquals(ToolResult.Kind.IMAGE_URL, result.kind);
        assertTrue(result.wireText().startsWith("NASA_IMAGE:https://apod.nasa.gov/image.jpg::"));
        assertEquals(body, ToolResult.fromWire(result.wireText()).text);
    }

    @Test
    public void recordToolReply_withToolResultWireText_storesAssistantTurn() {
        conversation.enter();
        backend.nextReply = "{\"tool\":\"device\",\"params\":{\"action\":\"battery\"}}";
        conversation.send("Batterie ?", new ChatBackend.OnReply() {
            @Override public void onReply(String text) { /* tool json */ }
            @Override public void onError(String error) { fail(error); }
        });

        ToolResult toolOut = ToolResult.text("Tu es à 72 %.");
        conversation.recordToolReply(toolOut.wireText());

        List<ChatBackend.Turn> history = conversation.historySnapshot();
        assertEquals(2, history.size());
        assertEquals("Tu es à 72 %.", history.get(1).text);
        assertFalse(conversation.isUserTurnPending());
    }

    @Test
    public void completeEphemeral_doesNotTouchHistory() {
        conversation.enter();
        awaitReply(conversation, "Premier tour");
        int before = conversation.historySnapshot().size();
        assertEquals(2, before);

        AtomicReference<String> reply = new AtomicReference<>();
        conversation.completeEphemeral("Prompt bureau isolé", new ChatBackend.OnReply() {
            @Override
            public void onReply(String text) {
                reply.set(text);
            }

            @Override
            public void onError(String error) {
                fail(error);
            }
        }, ChatSendOptions.legacy());

        assertNotNull(reply.get());
        assertEquals(before, conversation.historySnapshot().size());
        assertTrue(backend.lastHistory.isEmpty());
        assertEquals("Prompt bureau isolé", backend.lastUserMessage);
    }

    @Test
    public void completeEphemeralSync_returnsWithoutHistory() throws Exception {
        conversation.enter();
        awaitReply(conversation, "Salut");
        backend.nextReply = "Réponse éphémère";

        String out = conversation.completeEphemeralSync("Sync prompt", 5, null);

        assertEquals("Réponse éphémère", out);
        assertEquals(2, conversation.historySnapshot().size());
        assertTrue(backend.lastHistory.isEmpty());
    }

    @Test
    public void completeEphemeralSync_marksEphemeralWhenChannelBureau() throws Exception {
        conversation.enter();
        backend.nextReply = "Réponse bureau";

        String out = conversation.completeEphemeralSync("Sync prompt", 5, "bureau");

        assertEquals("Réponse bureau", out);
        assertEquals(0, conversation.historySnapshot().size());
        assertTrue(backend.lastHistory.isEmpty());
    }

    @Test
    public void staleLlmCallback_afterLocalToolReply_doesNotOverwriteHistory() throws Exception {
        Trace.clear(RuntimeEnvironment.getApplication());
        conversation.enter();
        backend.deferSend = true;
        backend.nextError = "HTTP 429 rate limit exceeded";

        conversation.send("Première question lente", new ChatBackend.OnReply() {
            @Override public void onReply(String text) { fail("stale success"); }
            @Override public void onError(String error) { fail("stale error: " + error); }
        });

        conversation.addUserMessage("Tu as eut des problèmes ?");
        conversation.recordToolReply("6h30 · groq/openai/gpt-oss-120b → next : bilan diag local.");

        backend.deferSend = false;
        backend.flushDeferredSend();

        List<ChatBackend.Turn> history = conversation.historySnapshot();
        assertEquals(2, history.size());
        assertTrue(history.get(0).fromUser);
        assertEquals("Tu as eut des problèmes ?", history.get(0).text);
        assertFalse(history.get(1).fromUser);
        assertEquals("6h30 · groq/openai/gpt-oss-120b → next : bilan diag local.",
                history.get(1).text);
        assertFalse(history.get(1).text.contains("Réessaie"));
        assertFalse(conversation.isUserTurnPending());

        Trace.flushForTests();
        String jsonl = new String(java.nio.file.Files.readAllBytes(Trace.file().toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(jsonl.contains("\"type\":\"stale_callback_ignored\"")
                || jsonl.contains("\"type\": \"stale_callback_ignored\""));
        assertTrue(backend.providerTraceDiscarded);
        assertFalse(backend.providerTraceConsumed);
    }

    @Test
    public void staleLlmCallback_doesNotConsumeProviderTrace() {
        conversation.enter();
        backend.deferSend = true;
        backend.nextReply = "Réponse obsolète";

        conversation.send("Première question", new ChatBackend.OnReply() {
            @Override public void onReply(String text) { fail("stale"); }
            @Override public void onError(String error) { fail("stale"); }
        });

        conversation.addUserMessage("Deuxième question");
        conversation.recordToolReply("Réponse locale.");

        backend.deferSend = false;
        backend.flushDeferredSend();

        assertTrue(backend.providerTraceDiscarded);
        assertFalse(backend.providerTraceConsumed);
    }

    @Test
    public void staleAgenticError_afterLocalToolReply_doesNotPoisonHistory() {
        conversation.enter();
        conversation.addUserMessage("Quel temps ?");
        conversation.recordToolSuccessHint("weather", "18°C, soleil");

        backend.nextError = "HTTP 429 rate limit exceeded";
        backend.deferAgentic = true;
        AgenticChain chain = new AgenticChain(conversation.historySnapshot(),
                conversation.getLastUserText());
        chain.addStep(LlmReply.text(""), new NativeToolCall("c1", "weather",
                        new org.json.JSONObject()),
                "18°C", "18°C");

        conversation.sendAgenticStep(chain, ChatSendOptions.agenticStep(
                ChatSendOptions.legacy().allowedTools, false), new ChatBackend.OnReply() {
            @Override public void onReply(String text) { fail("stale agentic success"); }
            @Override public void onError(String error) { fail("stale agentic error: " + error); }
        });

        conversation.addUserMessage("Tu as eut des problèmes ?");
        conversation.recordToolReply("Pas d'erreur notable aujourd'hui.");

        backend.deferAgentic = false;
        backend.flushDeferredAgentic();

        List<ChatBackend.Turn> history = conversation.historySnapshot();
        assertEquals(2, history.size());
        assertEquals("Pas d'erreur notable aujourd'hui.", history.get(1).text);
        assertNotEquals(ChatSpokenErrors.HISTORY_SAFE_TRANSIENT_ERROR, history.get(1).text);
    }

    @Test
    public void stripPoisonOnNextSend_removesStaleTransientError() {
        conversation.enter();
        backend.nextError = "HTTP 429 rate limit exceeded";
        conversation.send("Briefing", new ChatBackend.OnReply() {
            @Override public void onReply(String text) { fail("expected error"); }
            @Override public void onError(String error) { /* shown to user */ }
        });
        assertEquals(1, conversation.historySnapshot().size());

        backend.nextError = null;
        backend.nextReply = "OK";
        awaitReply(conversation, "Tu as eut des problèmes ?");

        List<ChatBackend.Turn> history = conversation.historySnapshot();
        assertEquals(4, history.size());
        assertEquals("Tu as eut des problèmes ?", history.get(2).text);
        assertEquals("OK", history.get(3).text);
        for (ChatBackend.Turn t : history) {
            assertNotEquals(ChatSpokenErrors.HISTORY_SAFE_TRANSIENT_ERROR, t.text);
        }
    }

    @Test
    public void enter_stripsPoisonLoadedFromMemory() {
        memory.setRecentTurns(Arrays.asList(
                new ChatBackend.Turn(true, "Salut"),
                new ChatBackend.Turn(false, ChatSpokenErrors.HISTORY_SAFE_TRANSIENT_ERROR),
                new ChatBackend.Turn(true, "Ça va ?"),
                new ChatBackend.Turn(false, "Oui")));

        conversation.enter();

        List<ChatBackend.Turn> history = conversation.historySnapshot();
        assertEquals(3, history.size());
        assertEquals("Salut", history.get(0).text);
        assertEquals("Ça va ?", history.get(1).text);
        assertEquals("Oui", history.get(2).text);
    }

    @Test
    public void longAssistantReply_preservedInNextSend() {
        StringBuilder longReply = new StringBuilder(
                "Mais attention, le maté, c'est aussi de la caféine ; ");
        while (longReply.length() < 900) {
            longReply.append("nuance importante sur la consommation. ");
        }
        longReply.append("survol possible si tu en abuses.");
        backend.nextReply = longReply.toString();

        conversation.enter();
        awaitReply(conversation, "C'est quoi le maté ?");
        assertTrue(conversation.historySnapshot().get(1).text.length() > 500);
        assertTrue(conversation.historySnapshot().get(1).text.contains("survol"));

        backend.nextReply = "ok";
        awaitReply(conversation, "Et la caféine dedans ?");

        ChatBackend.Turn priorAssistant = conversation.historySnapshot().get(1);
        assertTrue(priorAssistant.text.contains("survol"));
        assertTrue(backend.lastHistory.get(1).text.contains("survol"));
        assertTrue(backend.lastHistory.get(1).text.length() > 500);
    }

    private static void awaitReply(ConversationManager conversation, String message) {
        AtomicReference<String> reply = new AtomicReference<>();
        conversation.send(message, new ChatBackend.OnReply() {
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

    private static boolean lastTurnIsUser(List<ChatBackend.Turn> turns) {
        return !turns.isEmpty() && turns.get(turns.size() - 1).fromUser;
    }
}
