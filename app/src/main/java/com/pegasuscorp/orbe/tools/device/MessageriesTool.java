package com.pegasuscorp.orbe.tools.device;

import android.content.Context;
import android.content.Intent;

import com.pegasuscorp.orbe.inbox.InboxCategoryStore;
import com.pegasuscorp.orbe.inbox.InboxItem;
import com.pegasuscorp.orbe.inbox.SmsThreads;
import com.pegasuscorp.orbe.inbox.UnifiedInbox;
import com.pegasuscorp.orbe.tools.Tool;
import com.pegasuscorp.orbe.tools.ToolCallback;
import com.pegasuscorp.orbe.tools.ToolResult;
import com.pegasuscorp.orbe.tools.ToolTag;
import com.pegasuscorp.orbe.webmsg.WebMessagingActivity;
import com.pegasuscorp.orbe.webmsg.WebMsgSnapshotStore;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Messages SMS / WhatsApp / Messenger de la Messagerie unifiée : SMS lus sur le téléphone,
 * WhatsApp et Messenger par notifications (en direct) et relevés de l'écran Messagerie.
 */
public final class MessageriesTool implements Tool {

    private static final int MAX_CHATS = 12;
    /** Au-delà, le relevé web est signalé comme ancien. */
    private static final long STALE_MS = 15 * 60_000L;

    @Override
    public String id() {
        return "messageries";
    }

    @Override
    public ToolTag tag() {
        return ToolTag.NOTIFICATIONS;
    }

    @Override
    public String description() {
        return "messageries(action:\"unread\"|\"list\"|\"open\", "
                + "service?:\"sms\"|\"whatsapp\"|\"messenger\", categorie?:\"pro\"|\"perso\") — "
                + "messages SMS / WhatsApp / Messenger (tous si service absent ; "
                + "categorie = conversations rangées en Pro ou Perso). "
                + "unread=discussions non lues ; list=dernières discussions ; "
                + "open=ouvrir l'écran Messagerie (actualise WhatsApp/Messenger).";
    }

    @Override
    public void execute(Context ctx, JSONObject params, ToolCallback cb) {
        String action = params != null ? params.optString("action", "unread").trim().toLowerCase() : "unread";
        String service = params != null ? params.optString("service", "").trim().toLowerCase() : "";
        if (action.equals("open") || action.equals("ouvrir")) {
            Intent i = new Intent(ctx, WebMessagingActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (service.startsWith("mess")) {
                i.putExtra(WebMessagingActivity.EXTRA_SERVICE, WebMsgSnapshotStore.MESSENGER);
            } else if (service.startsWith("wh")) {
                i.putExtra(WebMessagingActivity.EXTRA_SERVICE, WebMsgSnapshotStore.WHATSAPP);
            }
            ctx.startActivity(i);
            cb.onSuccessAndExit(ToolResult.text("J'ouvre ta messagerie."));
            return;
        }
        boolean unreadOnly = !action.equals("list");
        boolean all = !service.startsWith("wh") && !service.startsWith("mess")
                && !service.startsWith("sms");
        List<InboxItem> items = UnifiedInbox.load(ctx);
        String categorie = params != null ? params.optString("categorie", "").trim().toLowerCase() : "";
        if (categorie.startsWith("pro") || categorie.startsWith("perso")) {
            InboxCategoryStore.Category wanted = categorie.startsWith("pro")
                    ? InboxCategoryStore.Category.PRO : InboxCategoryStore.Category.PERSO;
            List<InboxItem> kept = new ArrayList<>();
            for (InboxItem it : items) {
                if (InboxCategoryStore.get(ctx, it) == wanted) kept.add(it);
            }
            items = kept;
        }
        long now = System.currentTimeMillis();
        StringBuilder spoken = new StringBuilder();
        StringBuilder llm = new StringBuilder();
        if (all || service.startsWith("wh")) {
            describe("WhatsApp", only(items, InboxItem.Source.WHATSAPP),
                    webNote(WebMsgSnapshotStore.load(ctx, WebMsgSnapshotStore.WHATSAPP), now),
                    unreadOnly, spoken, llm);
        }
        if (all || service.startsWith("mess")) {
            describe("Messenger", only(items, InboxItem.Source.MESSENGER),
                    webNote(WebMsgSnapshotStore.load(ctx, WebMsgSnapshotStore.MESSENGER), now),
                    unreadOnly, spoken, llm);
        }
        if (all || service.startsWith("sms")) {
            if (SmsThreads.canRead(ctx)) {
                describe("SMS", only(items, InboxItem.Source.SMS), "à jour", unreadOnly,
                        spoken, llm);
            } else {
                spoken.append("SMS : accès non autorisé. ");
                llm.append("SMS : lecture non autorisée (ouvrir l'écran Messagerie pour l'accorder).\n");
            }
        }
        String guide = unreadOnly
                ? "Consigne de réponse : même pour une question oui/non, cite chaque discussion "
                + "non lue (nom, ou groupe + expéditeur) avec l'idée de son dernier message, "
                + "en une phrase courte chacune. Ne dis pas seulement qu'il y a des messages.\n"
                : "Consigne de réponse : résume les discussions les plus récentes "
                + "(nom + idée du dernier message).\n";
        cb.onSuccess(ToolResult.text(spoken.toString().trim(), guide + llm.toString().trim()));
    }

    private static List<InboxItem> only(List<InboxItem> items, InboxItem.Source source) {
        List<InboxItem> out = new ArrayList<>();
        for (InboxItem it : items) if (it.source == source) out.add(it);
        return out;
    }

    /** Fraîcheur : notifications en direct + âge du dernier relevé web. */
    static String webNote(WebMsgSnapshotStore.Snapshot snap, long now) {
        if (snap == null) return "notifications en direct ; jamais relevé sur le web";
        long ageMin = Math.max(0, (now - snap.takenAtMs) / 60_000L);
        String note = "notifications en direct ; relevé web "
                + (ageMin < 1 ? "à l'instant" : "il y a " + formatAge(ageMin));
        if (now - snap.takenAtMs > STALE_MS) {
            note += " (un message lu ailleurs peut encore apparaître non lu)";
        }
        return note;
    }

    /** Résumé parlé court + détail pour le LLM. Pur, testable. */
    static void describe(String label, List<InboxItem> items, String freshness,
            boolean unreadOnly, StringBuilder spoken, StringBuilder llm) {
        int unreadChats = 0;
        for (InboxItem it : items) if (it.isUnread()) unreadChats++;
        spoken.append(label).append(" : ").append(unreadOnly
                ? (unreadChats == 0 ? "rien de non lu" : unreadChats
                        + (unreadChats > 1 ? " discussions non lues" : " discussion non lue"))
                : items.size() + " discussions").append(". ");
        llm.append(label).append(" — ").append(freshness).append('\n');
        int n = 0;
        for (InboxItem c : items) {
            if (unreadOnly && !c.isUnread()) continue;
            if (n++ >= MAX_CHATS) break;
            llm.append("- ").append(c.group ? "groupe « " + c.name + " »" : c.name);
            if (c.unread == InboxItem.UNREAD_UNKNOWN_COUNT) {
                llm.append(" [non lu]");
            } else if (c.unread > 0) {
                llm.append(" [").append(c.unread).append(" non lu")
                        .append(c.unread > 1 ? "s" : "").append(']');
            }
            if (!c.timeLabel.isEmpty()) {
                llm.append(" (").append(c.timeLabel).append(')');
            } else if (c.timeMs > 0) {
                llm.append(" (").append(new SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE)
                        .format(new Date(c.timeMs))).append(')');
            }
            if (!c.preview.isEmpty()) {
                llm.append(c.group ? " — dernier message (expéditeur : texte) : " : " : ")
                        .append(c.preview, 0, Math.min(c.preview.length(), 200));
            }
            llm.append('\n');
        }
        if (n == 0) llm.append(unreadOnly ? "(aucune discussion non lue)\n" : "(liste vide)\n");
    }

    private static String formatAge(long min) {
        if (min < 60) return min + " min";
        long h = min / 60;
        if (h < 48) return h + " h";
        return (h / 24) + " jours";
    }
}
