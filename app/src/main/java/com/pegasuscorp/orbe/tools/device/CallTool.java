package com.pegasuscorp.orbe.tools.device;

import com.pegasuscorp.orbe.tools.ToolTag;

import com.pegasuscorp.orbe.tools.ToolResult;

import com.pegasuscorp.orbe.tools.Tool;
import com.pegasuscorp.orbe.tools.ToolCallback;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import org.json.JSONObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Lance un appel via le composeur téléphonique. */
public final class CallTool implements Tool {

    private static final Pattern DIGITS = Pattern.compile("(\\+?\\d[\\d\\s.-]{6,}\\d)");

    @Override
    public String id() {
        return "call";
    }

    @Override
    public ToolTag tag() {
        return ToolTag.CALL;
    }

    @Override
    public String description() {
        return "call(contact:str) — Appelle un contact ou un numéro du répertoire.";
    }

    @Override
    public void execute(Context ctx, JSONObject params, ToolCallback cb) {
        String contact = params.optString("contact", "").trim();
        if (contact.isEmpty()) {
            contact = params.optString("name", "").trim();
        }
        if (contact.isEmpty()) {
            cb.onError("Qui veux-tu appeler ?");
            return;
        }

        String number = extractDigits(contact);
        if (number == null) {
            number = ContactLookup.findPhone(ctx, contact);
        }
        if (number == null || number.isEmpty()) {
            cb.onError("Je n'ai pas trouvé « " + contact + " » dans tes contacts.");
            return;
        }

        Intent dial = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number)))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (dial.resolveActivity(ctx.getPackageManager()) != null) {
            ctx.startActivity(dial);
            cb.onSuccessAndExit(ToolResult.text("J'ouvre l'appel vers " + contact + "."));
        } else {
            cb.onError("Aucune application téléphone disponible.");
        }
    }

    private static String extractDigits(String raw) {
        Matcher m = DIGITS.matcher(raw);
        if (!m.find()) return null;
        return m.group(1).replaceAll("[\\s.-]", "");
    }
}
