package com.pegasuscorp.orbe.tools.device;

import com.pegasuscorp.orbe.tools.ToolTag;

import com.pegasuscorp.orbe.tools.ToolResult;

import com.pegasuscorp.orbe.tools.Tool;
import com.pegasuscorp.orbe.tools.ToolCallback;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;

import org.json.JSONObject;

/** Presse-papiers : lire ou coller (écrire) un texte. */
public final class ClipboardTool implements Tool {

    @Override
    public String id() {
        return "clipboard";
    }

    @Override
    public ToolTag tag() {
        return ToolTag.CLIPBOARD;
    }

    @Override
    public String description() {
        return "clipboard(action:\"get\"|\"set\", text?:str) — "
                + "get=lire presse-papiers ; set=écrire (text requis).";
    }

    @Override
    public void execute(Context ctx, JSONObject params, ToolCallback cb) {
        ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) {
            cb.onError("Presse-papiers indisponible.");
            return;
        }
        String action = params != null
                ? params.optString("action", "get").trim().toLowerCase()
                : "get";
        if (action.isEmpty()) action = "get";

        switch (action) {
            case "set":
            case "write":
            case "paste":
            case "copy":
            case "coller":
            case "copier": {
                String text = params != null ? params.optString("text", "").trim() : "";
                if (text.isEmpty() && params != null) {
                    text = params.optString("message", "").trim();
                }
                if (text.isEmpty()) {
                    cb.onError("Quel texte veux-tu mettre dans le presse-papiers ?");
                    return;
                }
                cm.setPrimaryClip(ClipData.newPlainText("orbe", text));
                cb.onSuccess(ToolResult.text("C'est dans le presse-papiers."));
                break;
            }
            case "get":
            case "read":
            case "lire":
            case "status":
            default: {
                if (!cm.hasPrimaryClip()) {
                    cb.onSuccess(ToolResult.text("Le presse-papiers est vide."));
                    return;
                }
                ClipData clip = cm.getPrimaryClip();
                if (clip == null || clip.getItemCount() == 0) {
                    cb.onSuccess(ToolResult.text("Le presse-papiers est vide."));
                    return;
                }
                CharSequence raw = clip.getItemAt(0).coerceToText(ctx);
                String text = raw != null ? raw.toString().trim() : "";
                if (text.isEmpty()) {
                    cb.onSuccess(ToolResult.text("Le presse-papiers est vide."));
                    return;
                }
                if (text.length() > 500) {
                    text = text.substring(0, 497).trim() + "…";
                }
                cb.onSuccess(ToolResult.text("Presse-papiers : " + text));
            }
        }
    }
}
