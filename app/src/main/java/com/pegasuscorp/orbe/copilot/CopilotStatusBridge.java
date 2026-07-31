package com.pegasuscorp.orbe.copilot;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

/**
 * Messages de statut / erreur vers la bulle copilote (process principal).
 */
public final class CopilotStatusBridge {

    public static final String ACTION_STATUS =
            "com.pegasuscorp.orbe.copilot.STATUS";

    public static final String EXTRA_STATUS = "status";
    public static final String EXTRA_ERROR = "error";
    public static final String EXTRA_CLEAR = "clear";
    public static final String EXTRA_POSTED_AT = "posted_at";

    private CopilotStatusBridge() {}

    public static void postError(Context ctx, String message) {
        if (ctx == null || message == null || message.isEmpty()) return;
        Intent i = new Intent(ACTION_STATUS);
        i.setPackage(ctx.getPackageName());
        i.putExtra(EXTRA_ERROR, message);
        i.putExtra(EXTRA_POSTED_AT, SystemClock.elapsedRealtime());
        ctx.getApplicationContext().sendBroadcast(i);
    }

    public static void postStatus(Context ctx, String message) {
        if (ctx == null || message == null || message.isEmpty()) return;
        Intent i = new Intent(ACTION_STATUS);
        i.setPackage(ctx.getPackageName());
        i.putExtra(EXTRA_STATUS, message);
        i.putExtra(EXTRA_POSTED_AT, SystemClock.elapsedRealtime());
        ctx.getApplicationContext().sendBroadcast(i);
    }

    /** Efface le bandeau statut (après fin d'outil / erreur). */
    public static void clearStatus(Context ctx) {
        if (ctx == null) return;
        Intent i = new Intent(ACTION_STATUS);
        i.setPackage(ctx.getPackageName());
        i.putExtra(EXTRA_CLEAR, true);
        i.putExtra(EXTRA_POSTED_AT, SystemClock.elapsedRealtime());
        ctx.getApplicationContext().sendBroadcast(i);
    }
}
