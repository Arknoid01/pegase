package com.pegasuscorp.orbe.tools.device;

import com.pegasuscorp.orbe.tools.ToolTag;

import com.pegasuscorp.orbe.tools.ToolResult;

import com.pegasuscorp.orbe.tools.Tool;
import com.pegasuscorp.orbe.tools.ToolCallback;

import android.content.Context;
import android.content.Intent;

import org.json.JSONObject;

/**
 * Partage un texte via le sélecteur Android (WhatsApp, Drive, etc.).
 */
public final class ShareTool implements Tool {

    @Override
    public String id() {
        return "share";
    }

    @Override
    public ToolTag tag() {
        return ToolTag.SHARE;
    }

    @Override
    public String description() {
        return "share(text:str, title?:str) — Ouvre le partage Android (text/plain). "
                + "Utilise pour « partage ça », « envoie ce texte à… ». "
                + "Exemple : {\"tool\":\"share\",\"params\":{\"text\":\"Voici le récap du brief.\"}}.";
    }

    @Override
    public void execute(Context ctx, JSONObject params, ToolCallback cb) {
        String text = params != null ? params.optString("text", "").trim() : "";
        if (text.isEmpty() && params != null) {
            text = params.optString("message", "").trim();
        }
        if (text.isEmpty() && params != null) {
            text = params.optString("body", "").trim();
        }
        if (text.isEmpty()) {
            cb.onError("Précise le texte à partager.");
            return;
        }
        String title = params != null ? params.optString("title", "").trim() : "";
        if (title.isEmpty()) title = "Partager";

        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, text)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Intent chooser = Intent.createChooser(send, title)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        if (chooser.resolveActivity(ctx.getPackageManager()) != null) {
            ctx.startActivity(chooser);
            cb.onSuccessAndExit(ToolResult.text("Voici le partage — choisis l'app."));
        } else {
            cb.onError("Aucune application de partage disponible.");
        }
    }
}
