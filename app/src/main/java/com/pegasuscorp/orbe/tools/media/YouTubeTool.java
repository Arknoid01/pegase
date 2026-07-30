package com.pegasuscorp.orbe.tools.media;

import com.pegasuscorp.orbe.tools.ToolTag;

import com.pegasuscorp.orbe.tools.ToolResult;

import com.pegasuscorp.orbe.tools.Tool;
import com.pegasuscorp.orbe.tools.ToolCallback;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import org.json.JSONObject;

/**
 * Lance YouTube : recherche ou lecture.
 */
public final class YouTubeTool implements Tool {

    @Override
    public String id() {
        return "youtube";
    }

    @Override
    public ToolTag tag() {
        return ToolTag.YOUTUBE;
    }

    @Override
    public String description() {
        return "youtube(action:\"play\"|\"search\"|\"open\", query?:str)"
                + " — Ouvre YouTube avec une recherche ou une vidéo.";
    }

    @Override
    public void execute(Context ctx, JSONObject params, ToolCallback cb) {
        String action = params.optString("action", "search").toLowerCase();
        String query = params.optString("query", "").trim();

        if ("open".equals(action) && query.isEmpty()) {
            open(ctx, "https://www.youtube.com");
            cb.onSuccessAndExit(ToolResult.text("J'ouvre YouTube."));
            return;
        }
        if (query.isEmpty()) {
            cb.onError("Précise quoi chercher sur YouTube.");
            return;
        }

        String encoded = Uri.encode(query);
        if ("play".equals(action) || "search".equals(action)) {
            if (tryView(ctx, "vnd.youtube://results?search_query=" + encoded)) {
                cb.onSuccessAndExit(ToolResult.text("Je cherche " + query + " sur YouTube."));
                return;
            }
            open(ctx, "https://www.youtube.com/results?search_query=" + encoded);
            cb.onSuccessAndExit(ToolResult.text("Je cherche " + query + " sur YouTube."));
            return;
        }
        cb.onError("Action YouTube inconnue.");
    }

    private static boolean tryView(Context ctx, String uri) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (intent.resolveActivity(ctx.getPackageManager()) != null) {
            ctx.startActivity(intent);
            return true;
        }
        return false;
    }

    private static void open(Context ctx, String url) {
        ctx.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
}
