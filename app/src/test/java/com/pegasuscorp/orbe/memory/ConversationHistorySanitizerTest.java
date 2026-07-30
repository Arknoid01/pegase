package com.pegasuscorp.orbe.memory;

import com.pegasuscorp.orbe.chat.ChatSpokenErrors;
import com.pegasuscorp.orbe.chat.ChatBackend;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class ConversationHistorySanitizerTest {

    @Test
    public void forAssistant_stripsThinkingAndTruncates() {
        StringBuilder longBody = new StringBuilder("Réponse propre. ");
        // Dépasser MAX_ASSISTANT_CHARS
        while (longBody.length() < ConversationHistorySanitizer.MAX_ASSISTANT_CHARS + 200) {
            longBody.append("phrase suite ");
        }
        String raw = "<think>secret</think>" + longBody;

        String out = ConversationHistorySanitizer.forAssistant(raw);

        assertFalse(out.contains("redacted_thinking"));
        assertFalse(out.contains("secret"));
        assertTrue(out.startsWith("Réponse propre."));
        assertTrue(out.endsWith("…"));
        assertTrue(out.length() <= ConversationHistorySanitizer.MAX_ASSISTANT_CHARS + 1);
    }

    @Test
    public void forAssistant_replacesGroqQuotaPoison() {
        String poison = "Limite de requêtes Groq atteinte (quota API). Attends une minute.";
        assertEquals(ChatSpokenErrors.HISTORY_SAFE_TRANSIENT_ERROR,
                ConversationHistorySanitizer.forAssistant(poison));
        assertTrue(ChatSpokenErrors.isHistoryPoison(poison));
    }

    @Test
    public void normalize_stripsQuotaMessagesFromHistory() {
        List<ChatBackend.Turn> in = Arrays.asList(
                new ChatBackend.Turn(true, "Salut"),
                new ChatBackend.Turn(false,
                        "Limite de requêtes Groq atteinte (quota API). Attends une minute."),
                new ChatBackend.Turn(true, "Ça va ?"),
                new ChatBackend.Turn(false, "Oui."));
        List<ChatBackend.Turn> out = ConversationHistorySanitizer.normalize(in);
        assertEquals(4, out.size());
        assertEquals(ChatSpokenErrors.HISTORY_SAFE_TRANSIENT_ERROR, out.get(1).text);
        assertFalse(out.get(1).text.toLowerCase().contains("groq"));
    }

    @Test
    public void normalize_collapsesConsecutiveUsers() {
        List<ChatBackend.Turn> in = Arrays.asList(
                new ChatBackend.Turn(true, "premier"),
                new ChatBackend.Turn(true, "  deuxième  "),
                new ChatBackend.Turn(false, "ok"),
                new ChatBackend.Turn(true, "orphelin"));

        List<ChatBackend.Turn> out = ConversationHistorySanitizer.normalize(in);

        assertEquals(2, out.size());
        assertTrue(out.get(0).fromUser);
        assertEquals("deuxième", out.get(0).text);
        assertFalse(out.get(1).fromUser);
        assertEquals("ok", out.get(1).text);
    }

    @Test
    public void normalizeKeepingTrailingUser_preservesOrphanUser() {
        List<ChatBackend.Turn> in = Arrays.asList(
                new ChatBackend.Turn(true, "premier"),
                new ChatBackend.Turn(false, "ok"),
                new ChatBackend.Turn(true, "en attente"));

        List<ChatBackend.Turn> out =
                ConversationHistorySanitizer.normalizeKeepingTrailingUser(in);

        assertEquals(3, out.size());
        assertEquals("en attente", out.get(2).text);
        assertTrue(out.get(2).fromUser);
    }

    @Test
    public void normalize_capsStoredTurns() {
        ChatBackend.Turn[] turns = new ChatBackend.Turn[20];
        for (int i = 0; i < turns.length; i++) {
            turns[i] = new ChatBackend.Turn(i % 2 == 0, "t" + i);
        }
        List<ChatBackend.Turn> out = ConversationHistorySanitizer.normalize(Arrays.asList(turns));
        assertEquals(ConversationHistorySanitizer.MAX_STORED_TURNS, out.size());
        assertEquals("t6", out.get(0).text);
    }
}
