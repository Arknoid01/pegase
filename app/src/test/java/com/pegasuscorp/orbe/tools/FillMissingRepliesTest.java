package com.pegasuscorp.orbe.tools;

import static org.junit.Assert.assertEquals;

import com.pegasuscorp.orbe.chat.ChatBackend;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class FillMissingRepliesTest {

    private static ChatBackend.Turn u(String t) { return new ChatBackend.Turn(true, t); }
    private static ChatBackend.Turn a(String t) { return new ChatBackend.Turn(false, t); }

    @Test
    public void reponseSensibleNonSauvegardee_estAffichee() {
        List<ChatBackend.Turn> stored = Arrays.asList(u("salut"), a("coucou"), u("mes messages ?"));
        List<ChatBackend.Turn> live = Arrays.asList(u("salut"), a("coucou"),
                u("mes messages ?"), new ChatBackend.Turn(false, true, "hint"), a("3 non lus"));
        List<ChatBackend.Turn> out = PegaseInterfaceData.fillMissingReplies(stored, live);
        assertEquals(4, out.size());
        assertEquals("3 non lus", out.get(3).text);
    }

    @Test
    public void tourDejaRepondu_inchange() {
        List<ChatBackend.Turn> stored = Arrays.asList(u("salut"), a("coucou"));
        List<ChatBackend.Turn> live = Arrays.asList(u("salut"), a("autre"));
        assertEquals(stored, PegaseInterfaceData.fillMissingReplies(stored, live));
    }

    @Test
    public void demandeEnCours_sansReponse_resteSeule() {
        List<ChatBackend.Turn> stored = Arrays.asList(u("salut"), a("coucou"), u("question"));
        List<ChatBackend.Turn> live = Arrays.asList(u("salut"), a("coucou"), u("question"));
        assertEquals(3, PegaseInterfaceData.fillMissingReplies(stored, live).size());
    }
}
