package com.pegasuscorp.orbe.inbox;

import android.content.Context;

import com.pegasuscorp.orbe.webmsg.WebMsgSnapshotStore;

import java.util.ArrayList;
import java.util.List;

/**
 * Messagerie unifiée : SMS (lus sur le téléphone) + WhatsApp et Messenger (derniers relevés
 * de l'écran Messagerie), triés du plus récent au plus ancien.
 */
public final class UnifiedInbox {

    private static final int MAX_SMS_THREADS = 40;

    private UnifiedInbox() {}

    /** À appeler hors du thread principal (lecture SMS). */
    public static List<InboxItem> load(Context ctx) {
        return merge(SmsThreads.latest(ctx, MAX_SMS_THREADS),
                WebMsgSnapshotStore.load(ctx, WebMsgSnapshotStore.WHATSAPP),
                WebMsgSnapshotStore.load(ctx, WebMsgSnapshotStore.MESSENGER));
    }

    /** Pur, testable. */
    static List<InboxItem> merge(List<InboxItem> sms, WebMsgSnapshotStore.Snapshot whatsapp,
            WebMsgSnapshotStore.Snapshot messenger) {
        List<InboxItem> out = new ArrayList<>();
        if (sms != null) out.addAll(sms);
        addSnapshot(out, InboxItem.Source.WHATSAPP, whatsapp, false);
        addSnapshot(out, InboxItem.Source.MESSENGER, messenger, true);
        // Le relevé date de takenAtMs : les libellés (« 2 h ») sont relatifs à ce moment-là.
        out.sort((a, b) -> Long.compare(b.timeMs, a.timeMs));
        return out;
    }

    private static void addSnapshot(List<InboxItem> out, InboxItem.Source source,
            WebMsgSnapshotStore.Snapshot snap, boolean unreadWithoutCount) {
        if (snap == null) return;
        long ref = snap.takenAtMs > 0 ? snap.takenAtMs : System.currentTimeMillis();
        for (WebMsgSnapshotStore.Chat c : snap.chats) {
            int unread = c.unread == 0 ? 0
                    : unreadWithoutCount ? InboxItem.UNREAD_UNKNOWN_COUNT : c.unread;
            out.add(new InboxItem(source, c.name, c.last, InboxTimeParser.parse(c.time, ref),
                    c.time, unread, c.group, ""));
        }
    }
}
