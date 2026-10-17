package com.pegasuscorp.orbe.inbox;

import static org.junit.Assert.assertEquals;

import com.pegasuscorp.orbe.webmsg.WebMsgSnapshotStore;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class UnifiedInboxTest {

    @Test
    public void fusionTrieeEtNonLuMessengerSansNombre() {
        long now = 1_800_000_000_000L;
        InboxItem sms = new InboxItem(InboxItem.Source.SMS, "Maman", "Bisous",
                now - 60_000L, "", 1, false, "+33600000000");
        WebMsgSnapshotStore.Snapshot wa = new WebMsgSnapshotStore.Snapshot("whatsapp", now,
                Collections.singletonList(new WebMsgSnapshotStore.Chat(
                        "Sarah", "", "Ouii", 9, false)));
        WebMsgSnapshotStore.Snapshot ms = new WebMsgSnapshotStore.Snapshot("messenger", now,
                Arrays.asList(new WebMsgSnapshotStore.Chat("Louise", "2 h", "Coucou", 1, false),
                        new WebMsgSnapshotStore.Chat("Kevin", "12 min", "Ok", 0, false)));
        java.util.List<InboxItem> out = UnifiedInbox.merge(
                Collections.singletonList(sms), wa, ms);
        assertEquals(4, out.size());
        assertEquals("Maman", out.get(0).name);
        assertEquals("Kevin", out.get(1).name);
        assertEquals("Louise", out.get(2).name);
        assertEquals(InboxItem.UNREAD_UNKNOWN_COUNT, out.get(2).unread);
        // Date inconnue → en dernier.
        assertEquals("Sarah", out.get(3).name);
    }
}
