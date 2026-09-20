package com.pegasuscorp.orbe.iface;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.pegasuscorp.orbe.tools.PegaseInterfaceData;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class DiscussionSteeringBubbleTest {

    private static List<PegaseInterfaceData.ChatMessageUi> base() {
        List<PegaseInterfaceData.ChatMessageUi> l = new ArrayList<>();
        l.add(new PegaseInterfaceData.ChatMessageUi(true, "Toi", "météo et agenda", ""));
        return l;
    }

    @Test
    public void sansConsigne_laListeEstInchangee() {
        List<PegaseInterfaceData.ChatMessageUi> in = base();
        assertSame(in, DiscussionFragment.withSteeringNotes(in, Collections.emptyList()));
    }

    @Test
    public void chaqueConsigne_devientUneBulleUtilisateurEnFinDeFil() {
        List<PegaseInterfaceData.ChatMessageUi> out = DiscussionFragment.withSteeringNotes(
                base(), Arrays.asList("en silence", "et vite"));

        assertEquals(3, out.size());
        assertTrue(out.get(1).fromUser);
        assertEquals("↪ Consigne : en silence", out.get(1).text);
        assertEquals("↪ Consigne : et vite", out.get(2).text);
        assertEquals("météo et agenda", out.get(0).text);
    }
}
