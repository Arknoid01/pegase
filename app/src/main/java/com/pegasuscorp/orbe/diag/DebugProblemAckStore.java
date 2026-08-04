package com.pegasuscorp.orbe.diag;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Marque un problème debug comme résolu jusqu'à une nouvelle occurrence (ts plus récent).
 * Les logs JSONL restent ; seuls les alertes du dashboard sont filtrées.
 */
public final class DebugProblemAckStore {

    private static final String PREFS = "debug_problem_acks";
    private static final String KEY_WINDOW = "ui_window";
    private static final String KEY_SORT = "ui_sort";

    private DebugProblemAckStore() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Timestamp d'événement jusqu'auquel ce problème est considéré acquitté. */
    public static long ackedThroughMs(Context ctx, String problemId) {
        if (problemId == null || problemId.isEmpty()) return 0L;
        return prefs(ctx).getLong("ack_" + problemId, 0L);
    }

    public static boolean isAcked(Context ctx, String problemId, long eventAtMs) {
        long through = ackedThroughMs(ctx, problemId);
        if (through <= 0L) return false;
        // Acquitté tant qu'aucune occurrence plus récente que l'ack
        return eventAtMs <= through;
    }

    public static void acknowledge(Context ctx, String problemId, long eventAtMs) {
        if (problemId == null || problemId.isEmpty()) return;
        long ts = Math.max(eventAtMs, System.currentTimeMillis());
        prefs(ctx).edit().putLong("ack_" + problemId, ts).apply();
    }

    public static void acknowledgeAll(Context ctx, Iterable<String> problemIds) {
        SharedPreferences.Editor ed = prefs(ctx).edit();
        long now = System.currentTimeMillis();
        for (String id : problemIds) {
            if (id == null || id.isEmpty()) continue;
            ed.putLong("ack_" + id, now);
        }
        ed.apply();
    }

    public static void clearAcks(Context ctx) {
        SharedPreferences p = prefs(ctx);
        SharedPreferences.Editor ed = p.edit();
        for (String k : p.getAll().keySet()) {
            if (k.startsWith("ack_")) ed.remove(k);
        }
        ed.apply();
    }

    public static String getWindow(Context ctx) {
        return prefs(ctx).getString(KEY_WINDOW, DebugHealthSnapshot.Window.H24.id);
    }

    public static void setWindow(Context ctx, String windowId) {
        prefs(ctx).edit().putString(KEY_WINDOW, windowId).apply();
    }

    public static String getSort(Context ctx) {
        return prefs(ctx).getString(KEY_SORT, DebugHealthSnapshot.Sort.NEWEST.id);
    }

    public static void setSort(Context ctx, String sortId) {
        prefs(ctx).edit().putString(KEY_SORT, sortId).apply();
    }
}
