package com.pegasuscorp.orbe.tools.device;

import com.pegasuscorp.orbe.tools.ToolTag;

import com.pegasuscorp.orbe.tools.ToolResult;

import com.pegasuscorp.orbe.tools.Tool;
import com.pegasuscorp.orbe.tools.ToolCallback;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import org.json.JSONObject;

/** Navigation Google Maps / Waze. */
public final class NavigationTool implements Tool {

    @Override
    public String id() {
        return "navigation";
    }

    @Override
    public ToolTag tag() {
        return ToolTag.NAVIGATION;
    }

    @Override
    public String description() {
        return "navigation(destination:str, app?:\"maps\"|\"waze\"|\"auto\")"
                + " — Lance un itinéraire vers une adresse ou un lieu.";
    }

    @Override
    public void execute(Context ctx, JSONObject params, ToolCallback cb) {
        String destination = params.optString("destination", "").trim();
        if (destination.isEmpty()) {
            destination = params.optString("query", "").trim();
        }
        if (destination.isEmpty()) {
            cb.onError("Précise où tu veux aller.");
            return;
        }

        String app = params.optString("app", "auto").toLowerCase();
        if ("waze".equals(app)) {
            if (openWaze(ctx, destination)) {
                cb.onSuccessAndExit(ToolResult.text("J'ouvre Waze vers " + destination + "."));
                return;
            }
            cb.onError("Waze n'est pas installé.");
            return;
        }
        if ("maps".equals(app) || openMaps(ctx, destination)) {
            cb.onSuccessAndExit(ToolResult.text("J'ouvre l'itinéraire vers " + destination + "."));
            return;
        }
        if (openWaze(ctx, destination)) {
            cb.onSuccessAndExit(ToolResult.text("J'ouvre Waze vers " + destination + "."));
            return;
        }
        openWebMaps(ctx, destination);
        cb.onSuccessAndExit(ToolResult.text("J'ouvre Maps vers " + destination + "."));
    }

    private static boolean openMaps(Context ctx, String destination) {
        String encoded = Uri.encode(destination);
        Intent nav = new Intent(Intent.ACTION_VIEW,
                Uri.parse("google.navigation:q=" + encoded))
                .setPackage("com.google.android.apps.maps")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (nav.resolveActivity(ctx.getPackageManager()) != null) {
            ctx.startActivity(nav);
            return true;
        }
        Intent geo = new Intent(Intent.ACTION_VIEW,
                Uri.parse("geo:0,0?q=" + encoded))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (geo.resolveActivity(ctx.getPackageManager()) != null) {
            ctx.startActivity(geo);
            return true;
        }
        return false;
    }

    private static boolean openWaze(Context ctx, String destination) {
        Intent waze = new Intent(Intent.ACTION_VIEW,
                Uri.parse("waze://?q=" + Uri.encode(destination) + "&navigate=yes"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (waze.resolveActivity(ctx.getPackageManager()) != null) {
            ctx.startActivity(waze);
            return true;
        }
        return false;
    }

    private static void openWebMaps(Context ctx, String destination) {
        String url = "https://www.google.com/maps/dir/?api=1&destination="
                + Uri.encode(destination);
        ctx.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
}
