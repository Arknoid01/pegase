package com.pegasuscorp.orbe.diag;

import com.pegasuscorp.orbe.session.PegaseSession;
import com.pegasuscorp.orbe.tools.ToolDispatcher;
import com.pegasuscorp.orbe.tools.ToolRegistry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowLooper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class TraceEnrichedEventsTest {

    @Before
    public void setUp() {
        Trace.init(RuntimeEnvironment.getApplication());
        Trace.clear(RuntimeEnvironment.getApplication());
    }

    @Test
    public void historySnapshot_preservesFullTurnText() throws Exception {
        StringBuilder longAssistant = new StringBuilder("Mais attention, le maté ");
        while (longAssistant.length() < 650) {
            longAssistant.append("et la caféine ");
        }
        longAssistant.append("survol possible.");
        java.util.List<com.pegasuscorp.orbe.chat.ChatBackend.Turn> history = java.util.Arrays.asList(
                new com.pegasuscorp.orbe.chat.ChatBackend.Turn(true, "C'est quoi le maté ?"),
                new com.pegasuscorp.orbe.chat.ChatBackend.Turn(false, longAssistant.toString()));
        Trace.historySnapshot(history, "before_send");
        Trace.flushForTests();
        String jsonl = new String(Files.readAllBytes(Trace.file().toPath()), StandardCharsets.UTF_8);
        assertTrue(jsonl.contains("survol possible."));
        assertFalse(jsonl.contains(longAssistant.substring(0, 400) + "…"));
    }

    @Test
    public void llmReply_preservesFullText() throws Exception {
        StringBuilder longReply = new StringBuilder("Réponse longue ");
        while (longReply.length() < 650) {
            longReply.append("détail ");
        }
        longReply.append("fin.");
        Trace.llmReply(longReply.toString(), "Groq/test", 100, false, false, false, 500);
        Trace.flushForTests();
        String jsonl = new String(Files.readAllBytes(Trace.file().toPath()), StandardCharsets.UTF_8);
        assertTrue(jsonl.contains("fin."));
        assertFalse(jsonl.contains(longReply.substring(0, 400) + "…"));
    }

    @Test
    public void toolHesitation_truncatesUserMsgTo100() throws Exception {
        String longMsg = new String(new char[150]).replace('\0', 'a');
        Trace.toolHesitation("notepad", "phantom_action", "detail", longMsg);
        Trace.flushForTests();
        String jsonl = new String(Files.readAllBytes(Trace.file().toPath()), StandardCharsets.UTF_8);
        assertTrue(jsonl.contains("tool_hesitation"));
        assertTrue(jsonl.contains("user_msg"));
        assertFalse(jsonl.contains(longMsg));
    }

    @Test
    public void http400ToolValidation_logsFailureCtx() throws Exception {
        assertTrue(PegaseSession.isHttp400ToolValidation(
                "HTTP 400 : Failed to validate tool schema for function call"));
        Trace.toolFailureContext("llm", "http_400_tool_validation",
                "HTTP 400 : Failed to validate tool",
                "Allume la lampe");
        Trace.flushForTests();
        String jsonl = new String(Files.readAllBytes(Trace.file().toPath()), StandardCharsets.UTF_8);
        assertTrue(jsonl.contains("tool_failure_ctx"));
        assertTrue(jsonl.contains("http_400_tool_validation"));
        assertTrue(jsonl.contains("lampe") || jsonl.contains("Allume"));
    }

    @Test
    public void toolDispatcher_malformedLogsHesitation() throws Exception {
        ToolDispatcher dispatcher = new ToolDispatcher(new ToolRegistry());
        dispatcher.dispatch(RuntimeEnvironment.getApplication(),
                "Voici {\"tool\":\"notepad\"",
                new com.pegasuscorp.orbe.tools.ToolCallback() {
                    @Override public void onSuccess(com.pegasuscorp.orbe.tools.ToolResult result) {}
                    @Override public void onError(String error) {}
                    @Override public void onConfirmNeeded(String q, Runnable ok, Runnable no) {}
                });
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks();
        Trace.flushForTests();
        String jsonl = new String(Files.readAllBytes(Trace.file().toPath()), StandardCharsets.UTF_8);
        assertTrue(jsonl.contains("tool_hesitation"));
        assertTrue(jsonl.contains("malformed_tool"));
        assertTrue(jsonl.contains("notepad"));
    }

    @Test
    public void isHttp400ToolValidation_detectsGroqStyle() {
        assertTrue(PegaseSession.isHttp400ToolValidation(
                "HTTP 400 : {\"error\":{\"message\":\"Failed to validate tool\"}}"));
        assertTrue(PegaseSession.isHttp400ToolValidation(
                "Tool call validation failed: parameters for tool notepad did not match schema"));
        assertTrue(PegaseSession.isHttp400ToolValidation(
                "{\"error\":{\"code\":\"tool_use_failed\",\"message\":\"Tool use failed\"}}"));
        assertFalse(PegaseSession.isHttp400ToolValidation("HTTP 503 Service Unavailable"));
        assertFalse(PegaseSession.isHttp400ToolValidation("HTTP 400 Bad Request nothing"));
    }

    @Test
    public void summarizeToolValidationError_keepsFailedGeneration() {
        String raw = "HTTP 400 : {\"error\":{\"message\":\"Tool call validation failed\","
                + "\"failed_generation\":\"notepad|{\\\"x\\\":1}\"}}";
        String s = PegaseSession.summarizeToolValidationError(raw);
        assertTrue(s.contains("Tool call validation"));
        assertTrue(s.contains("gen="));
        assertTrue(s.contains("notepad"));
    }

    @Test
    public void phantomBlocked_includesReasonWhenProvided() throws Exception {
        Trace.phantomBlocked("note le bug sync", "notepad(add): bug notification",
                "diag_fallback_blocked");
        Trace.flushForTests();
        String jsonl = new String(Files.readAllBytes(Trace.file().toPath()), StandardCharsets.UTF_8);
        assertTrue(jsonl.contains("phantom_blocked"));
        assertTrue(jsonl.contains("diag_fallback_blocked"));
    }

    @Test
    public void bureauEditResult_logsPotentialHallucination() throws Exception {
        Trace.bureauEditResult(false, false, 40, "On avait essayé", true);
        Trace.flushForTests();
        String jsonl = new String(Files.readAllBytes(Trace.file().toPath()), StandardCharsets.UTF_8);
        assertTrue(jsonl.contains("bureau_edit"));
        assertTrue(jsonl.contains("potentialHallucination"));
    }
}
