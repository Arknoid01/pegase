package com.pegasuscorp.orbe.f1companion;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.HashSet;
import java.util.Set;

/**
 * Articles déjà vus + article en attente de notif.
 */
public final class F1NewsStore {

    private static final String PREFS = "f1_news_store";
    private static final String KEY_SEEN = "seen_ids";
    private static final String KEY_SEEDED = "seeded";
    private static final String KEY_PENDING_ID = "pending_id";
    private static final String KEY_PENDING_TITLE = "pending_title";
    private static final String KEY_PENDING_SUMMARY = "pending_summary";
    private static final String KEY_PENDING_LINK = "pending_link";
    private static final String KEY_PENDING_TEAM = "pending_team";
    private static final String KEY_LAST_POLL = "last_poll_ms";
    private static final int MAX_SEEN = 250;

    private F1NewsStore() {}

    public static boolean isSeeded(Context ctx) {
        return prefs(ctx).getBoolean(KEY_SEEDED, false);
    }

    public static void markSeeded(Context ctx) {
        prefs(ctx).edit().putBoolean(KEY_SEEDED, true).apply();
    }

    public static Set<String> getSeenIds(Context ctx) {
        Set<String> out = new HashSet<>();
        try {
            JSONArray arr = new JSONArray(prefs(ctx).getString(KEY_SEEN, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                String id = arr.optString(i, "");
                if (!id.isEmpty()) out.add(id);
            }
        } catch (Exception ignored) {}
        return out;
    }

    public static void markSeen(Context ctx, String id) {
        if (id == null || id.isEmpty()) return;
        Set<String> seen = getSeenIds(ctx);
        if (seen.contains(id)) return;
        seen.add(id);
        persistSeen(ctx, seen);
    }

    public static void markSeenAll(Context ctx, Iterable<String> ids) {
        Set<String> seen = getSeenIds(ctx);
        for (String id : ids) {
            if (id != null && !id.isEmpty()) seen.add(id);
        }
        persistSeen(ctx, seen);
        markSeeded(ctx);
    }

    public static boolean hasSeen(Context ctx, String id) {
        return id != null && getSeenIds(ctx).contains(id);
    }

    public static void setPending(Context ctx, String id, String title, String summary,
            String link, String teamLabel) {
        prefs(ctx).edit()
                .putString(KEY_PENDING_ID, id != null ? id : "")
                .putString(KEY_PENDING_TITLE, title != null ? title : "")
                .putString(KEY_PENDING_SUMMARY, summary != null ? summary : "")
                .putString(KEY_PENDING_LINK, link != null ? link : "")
                .putString(KEY_PENDING_TEAM, teamLabel != null ? teamLabel : "")
                .apply();
    }

    public static String getPendingId(Context ctx) {
        return prefs(ctx).getString(KEY_PENDING_ID, "");
    }

    public static String getPendingTitle(Context ctx) {
        return prefs(ctx).getString(KEY_PENDING_TITLE, "");
    }

    public static String getPendingSummary(Context ctx) {
        return prefs(ctx).getString(KEY_PENDING_SUMMARY, "");
    }

    public static String getPendingLink(Context ctx) {
        return prefs(ctx).getString(KEY_PENDING_LINK, "");
    }

    public static String getPendingTeam(Context ctx) {
        return prefs(ctx).getString(KEY_PENDING_TEAM, "");
    }

    public static boolean hasPending(Context ctx) {
        String id = getPendingId(ctx);
        return id != null && !id.isEmpty();
    }

    /** Acquitte l'article (vu) et vide le pending. */
    public static void acknowledgePending(Context ctx) {
        SharedPreferences p = prefs(ctx);
        String id = p.getString(KEY_PENDING_ID, "");
        SharedPreferences.Editor ed = p.edit()
                .remove(KEY_PENDING_ID)
                .remove(KEY_PENDING_TITLE)
                .remove(KEY_PENDING_SUMMARY)
                .remove(KEY_PENDING_LINK)
                .remove(KEY_PENDING_TEAM);
        ed.apply();
        if (id != null && !id.isEmpty()) markSeen(ctx, id);
    }

    public static void setLastPollMs(Context ctx, long ms) {
        prefs(ctx).edit().putLong(KEY_LAST_POLL, ms).apply();
    }

    public static long getLastPollMs(Context ctx) {
        return prefs(ctx).getLong(KEY_LAST_POLL, 0L);
    }

    public static void resetForTests(Context ctx) {
        prefs(ctx).edit().clear().apply();
    }

    private static void persistSeen(Context ctx, Set<String> seen) {
        JSONArray arr = new JSONArray();
        int i = 0;
        for (String id : seen) {
            if (i++ >= MAX_SEEN) break;
            arr.put(id);
        }
        // Si trop : garder les derniers ajoutés — Set non ordonné, OK pour anti-doublon
        prefs(ctx).edit().putString(KEY_SEEN, arr.toString()).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
