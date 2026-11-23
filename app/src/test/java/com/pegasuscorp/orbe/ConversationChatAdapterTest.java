package com.pegasuscorp.orbe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.pegasuscorp.orbe.diag.ReasoningCard;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class ConversationChatAdapterTest {

    private static ReasoningCard card(List<ReasoningCard.ToolStep> steps) {
        return card(steps, "");
    }

    private static ReasoningCard card(List<ReasoningCard.ToolStep> steps, String backend) {
        return new ReasoningCard("Conversation", steps, 0, null, null, null, null, backend, 0, 0,
                false, false, "");
    }

    @Test
    public void toolsSummary_indiqueLocalOuCloud() {
        assertEquals("📱 local · Qwen3.5-2B", ConversationChatAdapter.toolsSummary(
                card(new ArrayList<>(), "Local/Qwen3.5-2B")));
        assertEquals("☁️ Groq", ConversationChatAdapter.toolsSummary(
                card(new ArrayList<>(), "Groq/openai/gpt-oss-120b")));
    }

    @Test
    public void toolsSummary_videSansOutil() {
        assertEquals("", ConversationChatAdapter.toolsSummary(null));
        assertEquals("", ConversationChatAdapter.toolsSummary(card(new ArrayList<>())));
    }

    @Test
    public void toolsSummary_listeOutilsStatutEtDuree() {
        List<ReasoningCard.ToolStep> steps = new ArrayList<>();
        steps.add(new ReasoningCard.ToolStep("weather", "", "", 800, true));
        steps.add(new ReasoningCard.ToolStep("sms", "", "", 1300, false));
        String s = ConversationChatAdapter.toolsSummary(card(steps));
        assertTrue(s, s.startsWith("🔧 weather ✅ · sms ❌"));
        assertTrue(s, s.endsWith("2,1 s"));
    }

    @Test
    public void toolsSummary_plafonneEtCompteLesAutres() {
        List<ReasoningCard.ToolStep> steps = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            steps.add(new ReasoningCard.ToolStep("t" + i, "", "", 0, true));
        }
        String s = ConversationChatAdapter.toolsSummary(card(steps));
        assertTrue(s, s.contains("t3 ✅"));
        assertTrue(s, !s.contains("t4"));
        assertTrue(s, s.contains("+2"));
    }
}
