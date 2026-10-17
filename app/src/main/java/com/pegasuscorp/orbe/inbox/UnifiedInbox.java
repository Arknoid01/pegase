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
                WebMsgSnapshotStore.load(ctx, WebMsgSnapshotStore.MESSENGER),
                NotificationInboxStore.all(ctx));
    }

    /** Pur, testable. */
    static List<InboxItem> merge(List<InboxItem> sms, WebMsgSnapshotStore.Snapshot whatsapp,
            WebMsgSnapshotStore.Snapshot messenger,
            List<NotificationInboxStore.Conversation> notified) {
        List<InboxItem> out = new ArrayList<>();
        if (sms != null) out.addAll(sms);
        addSnapshot(out, InboxItem.Source.WHATSAPP, whatsapp, false);
        addSnapshot(out, InboxItem.Source.MESSENGER, messenger, true);
        if (notified != null) {
            for (NotificationInboxStore.Conversation c : notified) {
                overlay(out, c, c.source == InboxItem.Source.WHATSAPP ? whatsapp : messenger);
            }
        }
        // Le relevé date de takenAtMs : les libellés (« 2 h ») sont relatifs à ce moment-là.
        out.sort((a, b) -> Long.compare(b.timeMs, a.timeMs));
        return out;
    }

    /**
     * Notifications = temps réel ; relevé web = état complet au moment du relevé.
     * Le plus récent des deux l'emporte : une notification arrivée après le relevé met la
     * conversation à jour, un relevé plus récent (conversation lue ailleurs) a le dernier mot.
     */
    private static void overlay(List<InboxItem> out, NotificationInboxStore.Conversation c,
            WebMsgSnapshotStore.Snapshot snap) {
        NotificationInboxStore.Message last = c.last();
        if (last == null) return;
        long snapAt = snap != null ? snap.takenAtMs : 0L;
        int idx = -1;
        for (int i = 0; i < out.size(); i++) {
            InboxItem it = out.get(i);
            if (it.source == c.source && it.name.equals(c.name)) { idx = i; break; }
        }
        if (idx >= 0 && c.lastTimeMs <= snapAt) return;
        String preview = last.sender.isEmpty() ? last.text : last.sender + " : " + last.text;
        int unread = c.unread;
        if (idx >= 0 && unread > 0 && out.get(idx).unread > 0) {
            unread = Math.max(unread, out.get(idx).unread);
        }
        InboxItem merged = new InboxItem(c.source, c.name, preview, c.lastTimeMs, "", unread,
                c.group || (idx >= 0 && out.get(idx).group), "");
        if (idx >= 0) out.set(idx, merged);
        else out.add(merged);
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
