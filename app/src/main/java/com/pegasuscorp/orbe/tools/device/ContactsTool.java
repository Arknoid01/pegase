package com.pegasuscorp.orbe.tools.device;

import com.pegasuscorp.orbe.tools.ToolTag;

import com.pegasuscorp.orbe.tools.ToolResult;

import com.pegasuscorp.orbe.tools.Tool;
import com.pegasuscorp.orbe.tools.ToolCallback;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import org.json.JSONObject;

import java.util.List;

/**
 * Recherche dans les contacts, puis ouvre appel ou SMS.
 */
public final class ContactsTool implements Tool {

    @Override
    public String id() {
        return "contacts";
    }

    @Override
    public ToolTag tag() {
        return ToolTag.CONTACTS;
    }

    @Override
    public String description() {
        return "contacts(action:\"search\"|\"call\"|\"sms\", query:str, message?:str) — "
                + "Contact (READ_CONTACTS). search=liste ; call/sms=composeur "
                + "(message optionnel pour sms).";
    }

    @Override
    public void execute(Context ctx, JSONObject params, ToolCallback cb) {
        String action = params != null
                ? params.optString("action", "search").trim().toLowerCase()
                : "search";
        String query = params != null ? params.optString("query", "").trim() : "";
        if (query.isEmpty() && params != null) {
            query = params.optString("name", "").trim();
        }
        if (query.isEmpty() && params != null) {
            query = params.optString("contact", "").trim();
        }
        if (query.isEmpty()) {
            cb.onError("Qui cherches-tu dans les contacts ?");
            return;
        }

        if (!ContactLookup.hasPermission(ctx)) {
            cb.onError("Autorise l'accès aux contacts dans les réglages Android.");
            return;
        }

        switch (action) {
            case "call":
            case "appelle":
            case "appel":
                openCall(ctx, query, cb);
                break;
            case "sms":
            case "message":
            case "text":
                openSms(ctx, query,
                        params != null ? params.optString("message", "").trim() : "",
                        cb);
                break;
            case "search":
            case "find":
            case "lookup":
            default:
                search(ctx, query, cb);
        }
    }

    private static void search(Context ctx, String query, ToolCallback cb) {
        List<ContactLookup.Match> matches = ContactLookup.search(ctx, query, 5);
        if (matches.isEmpty()) {
            cb.onSuccess(ToolResult.text("Aucun contact trouvé pour « " + query + " »."));
            return;
        }
        StringBuilder sb = new StringBuilder();
        if (matches.size() == 1) {
            ContactLookup.Match m = matches.get(0);
            sb.append("J'ai trouvé ").append(m.displayName)
                    .append(" — ").append(m.phone);
        } else {
            sb.append("Plusieurs contacts pour « ").append(query).append(" » :\n");
            for (int i = 0; i < matches.size(); i++) {
                ContactLookup.Match m = matches.get(i);
                sb.append(i + 1).append(". ").append(m.displayName)
                        .append(" — ").append(m.phone).append('\n');
            }
            sb.append("Dis « appelle … » ou « SMS à … » pour préciser.");
        }
        cb.onSuccess(ToolResult.text(sb.toString().trim()));
    }

    private static void openCall(Context ctx, String query, ToolCallback cb) {
        ContactLookup.Match m = ContactLookup.findBest(ctx, query);
        if (m == null) {
            cb.onError("Je n'ai pas trouvé « " + query + " » dans tes contacts.");
            return;
        }
        Intent dial = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(m.phone)))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (dial.resolveActivity(ctx.getPackageManager()) != null) {
            ctx.startActivity(dial);
            cb.onSuccessAndExit(ToolResult.text("J'ouvre l'appel vers " + m.displayName + "."));
        } else {
            cb.onError("Aucune application téléphone disponible.");
        }
    }

    private static void openSms(Context ctx, String query, String message, ToolCallback cb) {
        ContactLookup.Match m = ContactLookup.findBest(ctx, query);
        if (m == null) {
            cb.onError("Je n'ai pas trouvé « " + query + " » dans tes contacts.");
            return;
        }
        Uri uri = Uri.parse("smsto:" + Uri.encode(m.phone));
        Intent intent = new Intent(Intent.ACTION_SENDTO, uri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (message != null && !message.isEmpty()) {
            intent.putExtra("sms_body", message);
        }
        if (intent.resolveActivity(ctx.getPackageManager()) != null) {
            ctx.startActivity(intent);
            cb.onSuccessAndExit(ToolResult.text(
                    "J'ai préparé un SMS pour " + m.displayName + "."));
        } else {
            cb.onError("Aucune application SMS disponible.");
        }
    }
}
