package com.pegasuscorp.orbe.tools.device;

import com.pegasuscorp.orbe.tools.ToolTag;

import com.pegasuscorp.orbe.tools.ToolResult;

import com.pegasuscorp.orbe.tools.Tool;
import com.pegasuscorp.orbe.tools.ToolCallback;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import org.json.JSONObject;

/**
 * Ouvre le composeur SMS avec le message pré-rempli (l'utilisateur envoie lui-même).
 */
public final class SmsTool implements Tool {

    @Override public String id() { return "sms"; }

    @Override public ToolTag tag() { return ToolTag.SMS; }

    @Override
    public String description() {
        return "sms(to?:str, message:str) — Pré-remplit le composeur SMS (to = numéro optionnel)";
    }

    @Override
    public void execute(Context ctx, JSONObject params, ToolCallback cb) {
        String to      = params.optString("to", "").trim();
        String message = params.optString("message", "").trim();

        if (message.isEmpty()) {
            cb.onError("Le message est vide.");
            return;
        }

        Uri uri = to.isEmpty()
                ? Uri.parse("smsto:")
                : Uri.parse("smsto:" + Uri.encode(to));

        Intent intent = new Intent(Intent.ACTION_SENDTO, uri)
                .putExtra("sms_body", message)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        if (intent.resolveActivity(ctx.getPackageManager()) != null) {
            ctx.startActivity(intent);
            cb.onSuccessAndExit(ToolResult.text("J'ai préparé le SMS, il ne te reste qu'à envoyer."));
        } else {
            cb.onError("Aucune application SMS disponible.");
        }
    }
}
