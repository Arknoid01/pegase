package com.pegasuscorp.orbe.inbox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.pegasuscorp.orbe.webmsg.WebMsgSnapshotStore;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class NotificationInboxTest {

    private static NotificationInboxStore.Message m(String text, long t) {
        return new NotificationInboxStore.Message("", text, t);
    }

    @Test
    public void miseAJourSansNouveauMessage_neResonnePas() {
        Context ctx = ApplicationProvider.getApplicationContext();
        InboxItem.Source wa = InboxItem.Source.WHATSAPP;
        assertTrue(NotificationInboxStore.record(ctx, wa, "Sarah", false,
                Collections.singletonList(m("Salut", 1000)), null));
        // WhatsApp re-poste la même notification : pas nouveau.
        assertFalse(NotificationInboxStore.record(ctx, wa, "Sarah", false,
                Collections.singletonList(m("Salut", 1000)), null));
        // Nouveau message : la notif contient les deux non-lus.
        assertTrue(NotificationInboxStore.record(ctx, wa, "Sarah", false,
                Arrays.asList(m("Salut", 1000), m("Tu viens ?", 2000)), null));
        NotificationInboxStore.Conversation c = NotificationInboxStore.get(ctx,
                NotificationInboxStore.keyOf(wa, "Sarah"));
        assertEquals(2, c.unread);
        assertEquals(2, c.messages.size());
        assertEquals("Tu viens ?", c.last().text);

        NotificationInboxStore.markRead(ctx, c.key());
        assertEquals(0, NotificationInboxStore.get(ctx, c.key()).unread);
        // Après lecture, seul un message plus récent compte.
        assertTrue(NotificationInboxStore.record(ctx, wa, "Sarah", false,
                Collections.singletonList(m("Allô ?", 3000)), null));
        assertEquals(1, NotificationInboxStore.get(ctx, c.key()).unread);
    }

    @Test
    public void notificationApresReleve_metAJourLaLigne() {
        NotificationInboxStore.Conversation c = new NotificationInboxStore.Conversation(
                InboxItem.Source.MESSENGER, "Louise", false,
                Collections.singletonList(m("Coucou", 5000)), 5000, 1);
        WebMsgSnapshotStore.Snapshot ms = new WebMsgSnapshotStore.Snapshot("messenger", 4000,
                Collections.singletonList(new WebMsgSnapshotStore.Chat(
                        "Louise", "2 sem.", "Vieux message", 0, false)));
        List<InboxItem> out = UnifiedInbox.merge(null, null, ms, Collections.singletonList(c));
        assertEquals(1, out.size());
        assertEquals("Coucou", out.get(0).preview);
        assertEquals(1, out.get(0).unread);
    }

    @Test
    public void releveApresNotification_aLeDernierMot() {
        NotificationInboxStore.Conversation c = new NotificationInboxStore.Conversation(
                InboxItem.Source.WHATSAPP, "Sarah", false,
                Collections.singletonList(m("Salut", 1000)), 1000, 1);
        WebMsgSnapshotStore.Snapshot wa = new WebMsgSnapshotStore.Snapshot("whatsapp", 9000,
                Collections.singletonList(new WebMsgSnapshotStore.Chat(
                        "Sarah", "20:42", "Salut", 0, false)));
        List<InboxItem> out = UnifiedInbox.merge(null, wa, null, Collections.singletonList(c));
        assertEquals(0, out.get(0).unread);
    }

    @Test
    public void titreWhatsAppNettoye() {
        assertEquals("On the road again !",
                MessagingNotificationHandler.clean("On the road again ! (3 messages)"));
        assertEquals("Sarah", MessagingNotificationHandler.clean("Sarah"));
    }
}
