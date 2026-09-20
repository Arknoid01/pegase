package com.pegasuscorp.orbe.tools.memory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.pegasuscorp.orbe.chat.ChatBackend;
import com.pegasuscorp.orbe.memory.SessionSummary;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class ConversationHistoryToolTest {

    private static SessionSummary session(String topic, String summary, String endedAt) {
        SessionSummary s = new SessionSummary();
        s.topic = topic;
        s.summary = summary;
        s.endedAt = endedAt;
        return s;
    }

    @Test
    public void trouveSessionsEtTours_sansAccents_recentsD_abord() {
        List<SessionSummary> sessions = Arrays.asList(
                session("Voyage", "Billets pour Lisbonne en mars", "2026-09-01"),
                session("Code", "Refonte du launcher", "2026-09-10"));
        List<ChatBackend.Turn> turns = Arrays.asList(
                new ChatBackend.Turn(true, "Rappelle-moi le voyage à Lisbonne"),
                new ChatBackend.Turn(false, "Tu partais en mars, oui."),
                new ChatBackend.Turn(false, true, "hint système lisbonne"));

        List<String> hits = ConversationHistoryTool.search("lisbonne", sessions, turns, 5);

        assertEquals(2, hits.size());
        assertTrue(hits.get(0).startsWith("Session (2026-09-01)"));
        assertTrue(hits.get(1).startsWith("Toi : Rappelle-moi"));
        assertEquals(Collections.emptyList(),
                ConversationHistoryTool.search("cuisine", sessions, turns, 5));
        assertEquals(1, ConversationHistoryTool.search("lisbonne", sessions, turns, 1).size());
    }
}
