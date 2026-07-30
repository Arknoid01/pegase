package com.pegasuscorp.orbe.f1companion;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * État live GP : enabled, session, events déjà notifiés, positions, cooldown.
 */
public final class F1LiveStore {

    public static final long EVENT_COOLDOWN_MS = 8L * 60L * 1000L;
    private static final String PREFS = "f1_live_store";
    private static final String KEY_ENABLED = "live_enabled";
    private static final String KEY_SESSION = "session_key";
    private static final String KEY_LAST_RC_MS = "last_rc_ms";
    private static final String KEY_LAST_EVENT_MS = "last_event_notif_ms";
    private static final String KEY_NOTIFIED = "notified_ids";
    private static final String KEY_POSITIONS = "positions_json";
    private static final String KEY_PENDING_ID = "pending_id";
    private static final String KEY_PENDING_TITLE = "pending_title";
    private static final String KEY_PENDING_BODY = "pending_body";
    private static final String KEY_SEEDED_SESSION = "seeded_session";
    private static final int MAX_NOTIFIED = 80;

    private F1LiveStore() {}

    public static boolean isEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_ENABLED, true);
    }

    public static void setEnabled(Context ctx, boolean enabled) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    public static int getSessionKey(Context ctx) {
        return prefs(ctx).getInt(KEY_SESSION, 0);
    }

    public static void setSessionKey(Context ctx, int sessionKey) {
        prefs(ctx).edit().putInt(KEY_SESSION, sessionKey).apply();
    }

    public static long getLastRaceControlMs(Context ctx) {
        return prefs(ctx).getLong(KEY_LAST_RC_MS, 0L);
    }

    public static void setLastRaceControlMs(Context ctx, long ms) {
        prefs(ctx).edit().putLong(KEY_LAST_RC_MS, ms).apply();
    }

    public static long getLastEventNotifMs(Context ctx) {
        return prefs(ctx).getLong(KEY_LAST_EVENT_MS, 0L);
    }

    public static void markEventNotified(Context ctx, String eventId) {
        Set<String> ids = getNotifiedIds(ctx);
        if (eventId != null && !eventId.isEmpty()) ids.add(eventId);
        persistNotified(ctx, ids);
        prefs(ctx).edit().putLong(KEY_LAST_EVENT_MS, System.currentTimeMillis()).apply();
    }

    public static boolean wasNotified(Context ctx, String eventId) {
        return eventId != null && getNotifiedIds(ctx).contains(eventId);
    }

    public static boolean tooSoonForAnother(Context ctx) {
        long last = getLastEventNotifMs(ctx);
        return last > 0 && (System.currentTimeMillis() - last) < EVENT_COOLDOWN_MS;
    }

    public static int getSeededSession(Context ctx) {
        return prefs(ctx).getInt(KEY_SEEDED_SESSION, 0);
    }

    public static void setSeededSession(Context ctx, int sessionKey) {
        prefs(ctx).edit().putInt(KEY_SEEDED_SESSION, sessionKey).apply();
    }

    public static Map<Integer, Integer> getPositions(Context ctx) {
        Map<Integer, Integer> map = new HashMap<>();
        try {
            JSONObject o = new JSONObject(prefs(ctx).getString(KEY_POSITIONS, "{}"));
            Iterator<String> keys = o.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                map.put(Integer.parseInt(k), o.optInt(k, 0));
            }
        } catch (Exception ignored) {}
        return map;
    }

    public static void setPositions(Context ctx, Map<Integer, Integer> positions) {
        JSONObject o = new JSONObject();
        if (positions != null) {
            for (Map.Entry<Integer, Integer> e : positions.entrySet()) {
                try {
                    o.put(String.valueOf(e.getKey()), e.getValue());
                } catch (Exception ignored) {}
            }
        }
        prefs(ctx).edit().putString(KEY_POSITIONS, o.toString()).apply();
    }

    public static void setPending(Context ctx, F1LiveEvent event) {
        if (event == null) return;
        prefs(ctx).edit()
                .putString(KEY_PENDING_ID, event.id)
                .putString(KEY_PENDING_TITLE, event.title)
                .putString(KEY_PENDING_BODY, event.body)
                .apply();
    }

    public static String getPendingBody(Context ctx) {
        return prefs(ctx).getString(KEY_PENDING_BODY, "");
    }

    public static String getPendingTitle(Context ctx) {
        return prefs(ctx).getString(KEY_PENDING_TITLE, "");
    }

    public static void clearPending(Context ctx) {
        prefs(ctx).edit()
                .remove(KEY_PENDING_ID)
                .remove(KEY_PENDING_TITLE)
                .remove(KEY_PENDING_BODY)
                .apply();
    }

    public static void resetSessionState(Context ctx) {
        prefs(ctx).edit()
                .remove(KEY_SESSION)
                .remove(KEY_LAST_RC_MS)
                .remove(KEY_NOTIFIED)
                .remove(KEY_POSITIONS)
                .remove(KEY_SEEDED_SESSION)
                .remove(KEY_PENDING_ID)
                .remove(KEY_PENDING_TITLE)
                .remove(KEY_PENDING_BODY)
                .apply();
    }

    public static void resetForTests(Context ctx) {
        prefs(ctx).edit().clear().apply();
    }

    private static Set<String> getNotifiedIds(Context ctx) {
        Set<String> out = new HashSet<>();
        try {
            JSONArray arr = new JSONArray(prefs(ctx).getString(KEY_NOTIFIED, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                String id = arr.optString(i, "");
                if (!id.isEmpty()) out.add(id);
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static void persistNotified(Context ctx, Set<String> ids) {
        JSONArray arr = new JSONArray();
        int i = 0;
        for (String id : ids) {
            if (i++ >= MAX_NOTIFIED) break;
            arr.put(id);
        }
        prefs(ctx).edit().putString(KEY_NOTIFIED, arr.toString()).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
