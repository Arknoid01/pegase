package com.pegasuscorp.orbe.copilot;

import android.content.Context;
import android.content.Intent;

/**
 * Messages de statut / erreur vers la bulle copilote (process principal).
 */
public final class CopilotStatusBridge {

    public static final String ACTION_STATUS =
            "com.pegasuscorp.orbe.copilot.STATUS";

    private CopilotStatusBridge() {}

    public static void postError(Context ctx, String message) {
        if (ctx == null || message == null || message.isEmpty()) return;
        Intent i = new Intent(ACTION_STATUS);
        i.setPackage(ctx.getPackageName());
        i.putExtra("error", message);
        ctx.getApplicationContext().sendBroadcast(i);
    }

    public static void postStatus(Context ctx, String message) {
        if (ctx == null || message == null || message.isEmpty()) return;
        Intent i = new Intent(ACTION_STATUS);
        i.setPackage(ctx.getPackageName());
        i.putExtra("status", message);
        ctx.getApplicationContext().sendBroadcast(i);
    }
}
