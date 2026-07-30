package com.pegasuscorp.orbe.tools.knowledge;

import com.pegasuscorp.orbe.tools.ToolTag;

import com.pegasuscorp.orbe.tools.ToolResult;

import com.pegasuscorp.orbe.tools.Tool;
import com.pegasuscorp.orbe.tools.ToolCallback;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import org.json.JSONObject;

/**
 * Ouvre le navigateur avec une recherche web.
 */
public final class WebSearchTool implements Tool {

    @Override public String id() { return "web_search"; }

    @Override public ToolTag tag() { return ToolTag.WEB_SEARCH; }

    @Override
    public String description() {
        return "web_search(query:str) — Ouvre le navigateur avec une recherche";
    }

    @Override
    public void execute(Context ctx, JSONObject params, ToolCallback cb) {
        String query = params.optString("query", "").trim();
        if (query.isEmpty()) {
            cb.onError("Aucune requête de recherche fournie.");
            return;
        }

        Uri uri = Uri.parse("https://www.google.com/search?q=" + Uri.encode(query));
        Intent intent = new Intent(Intent.ACTION_VIEW, uri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        if (intent.resolveActivity(ctx.getPackageManager()) != null) {
            ctx.startActivity(intent);
            cb.onSuccess(ToolResult.text("Je lance la recherche pour toi."));
        } else {
            cb.onError("Aucun navigateur disponible.");
        }
    }
}
