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
 * Ouvre le composeur e-mail avec destinataire / sujet / corps préremplis
 * (l'utilisateur envoie lui-même).
 */
public final class EmailTool implements Tool {

    @Override
    public String id() {
        return "email";
    }

    @Override
    public ToolTag tag() {
        return ToolTag.EMAIL;
    }

    @Override
    public String description() {
        return "email(to?:str, subject?:str, body?:str) — Pré-remplit le composeur "
                + "e-mail (mailto) ; l'utilisateur valide.";
    }

    @Override
    public void execute(Context ctx, JSONObject params, ToolCallback cb) {
        String to = params != null ? params.optString("to", "").trim() : "";
        String subject = params != null ? params.optString("subject", "").trim() : "";
        String body = params != null ? params.optString("body", "").trim() : "";
        if (body.isEmpty() && params != null) {
            body = params.optString("message", "").trim();
        }
        if (to.isEmpty() && subject.isEmpty() && body.isEmpty()) {
            cb.onError("Précise au moins un destinataire, un sujet ou un corps de message.");
            return;
        }

        Uri.Builder builder = Uri.parse("mailto:" + (to.isEmpty() ? "" : to)).buildUpon();
        if (!subject.isEmpty()) builder.appendQueryParameter("subject", subject);
        if (!body.isEmpty()) builder.appendQueryParameter("body", body);

        Intent intent = new Intent(Intent.ACTION_SENDTO, builder.build())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!to.isEmpty()) {
            intent.putExtra(Intent.EXTRA_EMAIL, new String[]{to});
        }
        if (!subject.isEmpty()) intent.putExtra(Intent.EXTRA_SUBJECT, subject);
        if (!body.isEmpty()) intent.putExtra(Intent.EXTRA_TEXT, body);

        if (intent.resolveActivity(ctx.getPackageManager()) != null) {
            ctx.startActivity(intent);
            cb.onSuccessAndExit(ToolResult.text(
                    "J'ai préparé l'e-mail, il ne te reste qu'à envoyer."));
        } else {
            cb.onError("Aucune application e-mail disponible.");
        }
    }
}
