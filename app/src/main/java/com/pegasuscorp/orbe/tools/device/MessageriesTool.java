package com.pegasuscorp.orbe.tools.device;

import android.content.Context;
import android.content.Intent;

import com.pegasuscorp.orbe.tools.Tool;
import com.pegasuscorp.orbe.tools.ToolCallback;
import com.pegasuscorp.orbe.tools.ToolResult;
import com.pegasuscorp.orbe.tools.ToolTag;
import com.pegasuscorp.orbe.webmsg.WebMessagingActivity;
import com.pegasuscorp.orbe.webmsg.WebMsgSnapshotStore;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Messages WhatsApp / Messenger relevés par le mini navigateur de messageries.
 * Lit le dernier relevé (pas de connexion directe aux services).
 */
public final class MessageriesTool implements Tool {

    private static final int MAX_CHATS = 12;
    /** Au-delà, le relevé est signalé comme ancien. */
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
        return "messageries(action:\"unread\"|\"list\"|\"open\", service?:\"whatsapp\"|\"messenger\") — "
                + "messages WhatsApp / Messenger. unread=discussions non lues ; "
                + "list=dernières discussions ; open=ouvrir l'écran Messageries (actualise).";
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
            }
            ctx.startActivity(i);
            cb.onSuccessAndExit(ToolResult.text("J'ouvre tes messageries."));
            return;
        }
        boolean unreadOnly = !action.equals("list");
        List<String> services = new ArrayList<>();
        if (!service.startsWith("mess")) services.add(WebMsgSnapshotStore.WHATSAPP);
        if (!service.startsWith("wh")) services.add(WebMsgSnapshotStore.MESSENGER);

        long now = System.currentTimeMillis();
        StringBuilder spoken = new StringBuilder();
        StringBuilder llm = new StringBuilder();
        for (String s : services) {
            WebMsgSnapshotStore.Snapshot snap = WebMsgSnapshotStore.load(ctx, s);
            describe(label(s), snap, unreadOnly, now, spoken, llm);
        }
        cb.onSuccess(ToolResult.text(spoken.toString().trim(), llm.toString().trim()));
    }

    /** Résumé parlé court + détail pour le LLM. Pur, testable. */
    static void describe(String label, WebMsgSnapshotStore.Snapshot snap, boolean unreadOnly,
            long now, StringBuilder spoken, StringBuilder llm) {
        if (snap == null) {
            spoken.append(label).append(" : pas encore relevé, ouvre l'écran Messageries. ");
            llm.append(label).append(" : aucun relevé (l'utilisateur doit ouvrir l'écran "
                    + "Messageries et se connecter).\n");
            return;
        }
        long ageMin = Math.max(0, (now - snap.takenAtMs) / 60_000L);
        String age = ageMin < 1 ? "à l'instant" : "il y a " + formatAge(ageMin);
        int unreadChats = snap.unreadChats();
        if (unreadOnly) {
            spoken.append(label).append(" : ").append(unreadChats == 0 ? "rien de non lu"
                    : unreadChats + (unreadChats > 1 ? " discussions non lues" : " discussion non lue"))
                    .append(" (relevé ").append(age).append("). ");
        } else {
            spoken.append(label).append(" : ").append(snap.chats.size())
                    .append(" discussions (relevé ").append(age).append("). ");
        }
        llm.append(label).append(" — relevé ").append(age);
        if (now - snap.takenAtMs > STALE_MS) {
            llm.append(" (ancien : proposer d'ouvrir l'écran Messageries pour actualiser)");
        }
        llm.append('\n');
        int n = 0;
        for (WebMsgSnapshotStore.Chat c : snap.chats) {
            if (unreadOnly && c.unread == 0) continue;
            if (n++ >= MAX_CHATS) break;
            llm.append("- ").append(c.group ? "groupe « " + c.name + " »" : c.name);
            if (c.unread > 0) llm.append(" [").append(c.unread).append(" non lu")
                    .append(c.unread > 1 ? "s" : "").append(']');
            if (!c.time.isEmpty()) llm.append(" (").append(c.time).append(')');
            if (!c.last.isEmpty()) {
                llm.append(c.group ? " — dernier message (expéditeur : texte) : " : " : ")
                        .append(c.last);
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

    private static String label(String service) {
        return WebMsgSnapshotStore.MESSENGER.equals(service) ? "Messenger" : "WhatsApp";
    }
}
