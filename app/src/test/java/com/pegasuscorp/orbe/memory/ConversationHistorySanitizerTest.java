package com.pegasuscorp.orbe.memory;

import com.pegasuscorp.orbe.chat.ChatSpokenErrors;
import com.pegasuscorp.orbe.chat.ChatBackend;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class ConversationHistorySanitizerTest {

    @Test
    public void forAssistant_stripsThinkingWithoutTruncating() {
        StringBuilder longBody = new StringBuilder("Réponse propre. ");
        while (longBody.length() < 1200) {
            longBody.append("phrase suite ");
        }
        String raw = "<think>secret</think>" + longBody;

        String out = ConversationHistorySanitizer.forAssistant(raw);

        assertFalse(out.contains("redacted_thinking"));
        assertFalse(out.contains("secret"));
        assertTrue(out.startsWith("Réponse propre."));
        assertFalse(out.endsWith("…"));
        assertTrue(out.length() > 1000);
    }

    @Test
    public void forDisplayAssistant_truncatesForUiOnly() {
        StringBuilder longBody = new StringBuilder("Affichage. ");
        while (longBody.length() < ConversationHistorySanitizer.MAX_DISPLAY_ASSISTANT_CHARS + 200) {
            longBody.append("mot ");
        }
        String out = ConversationHistorySanitizer.forDisplayAssistant(longBody.toString());
        assertTrue(out.endsWith("…"));
        assertTrue(out.length() <= ConversationHistorySanitizer.MAX_DISPLAY_ASSISTANT_CHARS + 1);
    }

    @Test
    public void forAssistant_dropsGroqQuotaPoison() {
        String poison = "Limite de requêtes Groq atteinte (quota API). Attends une minute.";
        assertEquals("", ConversationHistorySanitizer.forAssistant(poison));
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
        // « Salut » partait avec sa réponse en erreur : la question restait sinon sans
        // réponse, et la fusion des tours utilisateur consécutifs la faisait disparaître
        // sans laisser de trace. Une note unique remplace l'échange perdu.
        assertEquals(3, out.size());
        assertEquals(ChatSpokenErrors.LOST_EXCHANGE_NOTE, out.get(0).text);
        assertFalse(out.get(0).fromUser);
        assertEquals("Ça va ?", out.get(1).text);
        // La ponctuation finale est conservée : elle n'est retirée que lorsqu'une
        // tournure bannie a réellement été coupée, sinon toute question perdait son
        // point d'interrogation — et son intonation au TTS.
        assertEquals("Oui.", out.get(2).text);
    }

    @Test
    public void stripPoisonTurns_removesTransientAssistantReplies() {
        List<ChatBackend.Turn> in = Arrays.asList(
                new ChatBackend.Turn(true, "Salut"),
                new ChatBackend.Turn(false, ChatSpokenErrors.HISTORY_SAFE_TRANSIENT_ERROR),
                new ChatBackend.Turn(false, "Réponse valide"));
        List<ChatBackend.Turn> out = ConversationHistorySanitizer.stripPoisonTurns(in);
        assertEquals(2, out.size());
        assertEquals("Salut", out.get(0).text);
        assertEquals("Réponse valide", out.get(1).text);
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
