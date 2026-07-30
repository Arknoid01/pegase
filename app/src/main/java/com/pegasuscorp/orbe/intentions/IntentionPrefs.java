package com.pegasuscorp.orbe.intentions;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONObject;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * État Intentions : edges, anti-spam, workWifiSsid, snooze/fire par id.
 */
public final class IntentionPrefs {

    public static final int QUIET_START_DEFAULT = 22;
    public static final int QUIET_END_DEFAULT = 7;
    public static final int MAX_DAILY = 2;
    public static final long GLOBAL_COOLDOWN_MS = 4L * 60L * 60L * 1000L;
    public static final long SNOOZE_MS = 4L * 60L * 60L * 1000L;
    public static final int BATTERY_THRESHOLD = 20;

    /** NotificationManager.notify id stable. */
    public static final int NOTIFICATION_ID = 64042;

    private static final String PREFS = "pegase_intentions";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_QUIET_START = "quiet_start";
    private static final String KEY_QUIET_END = "quiet_end";
    private static final String KEY_WORK_WIFI_SSID = "work_wifi_ssid";
    private static final String KEY_CAR_BT_NAME = "car_bt_name";
    private static final String KEY_CAR_BT_CONNECTED = "car_bt_connected";
    private static final String KEY_LAST_SEEN_CAR_BT = "last_seen_car_bt";
    private static final String KEY_DRIVE_DESTINATION = "drive_destination";
    private static final String KEY_DRIVE_SPOTIFY_QUERY = "drive_spotify_query";
    private static final String KEY_SUPPRESSED = "suppressed_ids";
    private static final String KEY_LAST_FIRED_GLOBAL = "last_fired_global";
    private static final String KEY_LAST_FIRED_BY_ID = "last_fired_by_id";
    private static final String KEY_LAST_SEEN_BATTERY = "last_seen_battery_percent";
    private static final String KEY_LAST_SEEN_SSID = "last_seen_ssid";
    private static final String KEY_DAILY_COUNT = "daily_count";
    private static final String KEY_DAILY_COUNT_DATE = "daily_count_date";
    private static final String KEY_SNOOZED_UNTIL = "snoozed_until_by_id";
    private static final String KEY_ACTIVE_NOTIF = "active_notification_id";

    private IntentionPrefs() {}

    public static boolean isEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_ENABLED, true);
    }

    public static void setEnabled(Context ctx, boolean enabled) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    public static int getQuietStartHour(Context ctx) {
        return clampHour(prefs(ctx).getInt(KEY_QUIET_START, QUIET_START_DEFAULT));
    }

    public static int getQuietEndHour(Context ctx) {
        return clampHour(prefs(ctx).getInt(KEY_QUIET_END, QUIET_END_DEFAULT));
    }

    public static void setQuietHours(Context ctx, int startHour, int endHour) {
        prefs(ctx).edit()
                .putInt(KEY_QUIET_START, clampHour(startHour))
                .putInt(KEY_QUIET_END, clampHour(endHour))
                .apply();
    }

    public static String getWorkWifiSsid(Context ctx) {
        String s = prefs(ctx).getString(KEY_WORK_WIFI_SSID, "");
        return s == null ? "" : s.trim();
    }

    public static void setWorkWifiSsid(Context ctx, String ssid) {
        String s = ssid == null ? "" : ssid.trim();
        prefs(ctx).edit().putString(KEY_WORK_WIFI_SSID, s).apply();
    }

    public static String getCarBtName(Context ctx) {
        String s = prefs(ctx).getString(KEY_CAR_BT_NAME, "");
        return s == null ? "" : s.trim();
    }

    public static void setCarBtName(Context ctx, String name) {
        prefs(ctx).edit().putString(KEY_CAR_BT_NAME, name == null ? "" : name.trim()).apply();
    }

    public static boolean isCarBtConnected(Context ctx) {
        return prefs(ctx).getBoolean(KEY_CAR_BT_CONNECTED, false);
    }

    public static void setCarBtConnected(Context ctx, boolean connected) {
        prefs(ctx).edit().putBoolean(KEY_CAR_BT_CONNECTED, connected).apply();
    }

    public static boolean getLastSeenCarBtConnected(Context ctx) {
        return prefs(ctx).getBoolean(KEY_LAST_SEEN_CAR_BT, false);
    }

    public static void setLastSeenCarBtConnected(Context ctx, boolean connected) {
        prefs(ctx).edit().putBoolean(KEY_LAST_SEEN_CAR_BT, connected).apply();
    }

    /** Destination navigation au Oui conduite (ex. Maison, adresse). */
    public static String getDriveDestination(Context ctx) {
        String s = prefs(ctx).getString(KEY_DRIVE_DESTINATION, "");
        return s == null ? "" : s.trim();
    }

    public static void setDriveDestination(Context ctx, String destination) {
        prefs(ctx).edit()
                .putString(KEY_DRIVE_DESTINATION, destination == null ? "" : destination.trim())
                .apply();
    }

    /** Recherche Spotify optionnelle au Oui conduite (vide = reprise lecture). */
    public static String getDriveSpotifyQuery(Context ctx) {
        String s = prefs(ctx).getString(KEY_DRIVE_SPOTIFY_QUERY, "");
        return s == null ? "" : s.trim();
    }

    public static void setDriveSpotifyQuery(Context ctx, String query) {
        prefs(ctx).edit()
                .putString(KEY_DRIVE_SPOTIFY_QUERY, query == null ? "" : query.trim())
                .apply();
    }

    private static final String KEY_PREFER_EARLIER = "prefer_earlier_by_id";

    /** Hint learning : élargir la fenêtre « bientôt » pour cette intention. */
    public static boolean prefersEarlier(Context ctx, String id) {
        if (TextUtils.isEmpty(id)) return false;
        return readJsonLong(prefs(ctx).getString(KEY_PREFER_EARLIER, "{}"), id, 0L) > 0L;
    }

    public static void setPreferEarlier(Context ctx, String id, boolean enabled) {
        if (!IntentionIds.isValid(id)) return;
        putJsonLong(ctx, KEY_PREFER_EARLIER, id, enabled ? System.currentTimeMillis() : 0L);
    }

    public static Set<String> getSuppressedIds(Context ctx) {
        return prefs(ctx).getStringSet(KEY_SUPPRESSED, new HashSet<>());
    }

    public static boolean isSuppressed(Context ctx, String id) {
        Set<String> set = getSuppressedIds(ctx);
        return set != null && set.contains(id);
    }

    public static void suppress(Context ctx, String id) {
        if (!IntentionIds.isValid(id)) return;
        Set<String> next = new HashSet<>(getSuppressedIds(ctx));
        next.add(id);
        prefs(ctx).edit().putStringSet(KEY_SUPPRESSED, next).apply();
    }

    public static void unsuppress(Context ctx, String id) {
        Set<String> next = new HashSet<>(getSuppressedIds(ctx));
        next.remove(id);
        prefs(ctx).edit().putStringSet(KEY_SUPPRESSED, next).apply();
    }

    public static long getLastFiredGlobal(Context ctx) {
        return prefs(ctx).getLong(KEY_LAST_FIRED_GLOBAL, 0L);
    }

    public static long getLastFired(Context ctx, String id) {
        return readJsonLong(prefs(ctx).getString(KEY_LAST_FIRED_BY_ID, "{}"), id, 0L);
    }

    public static int getLastSeenBatteryPercent(Context ctx) {
        return prefs(ctx).getInt(KEY_LAST_SEEN_BATTERY, -1);
    }

    public static void setLastSeenBatteryPercent(Context ctx, int percent) {
        prefs(ctx).edit().putInt(KEY_LAST_SEEN_BATTERY, percent).apply();
    }

    public static String getLastSeenSsid(Context ctx) {
        String s = prefs(ctx).getString(KEY_LAST_SEEN_SSID, "");
        return s == null ? "" : s;
    }

    public static void setLastSeenSsid(Context ctx, String ssid) {
        prefs(ctx).edit().putString(KEY_LAST_SEEN_SSID, ssid == null ? "" : ssid).apply();
    }

    public static String getActiveNotificationId(Context ctx) {
        return prefs(ctx).getString(KEY_ACTIVE_NOTIF, null);
    }

    public static void setActiveNotificationId(Context ctx, String id) {
        SharedPreferences.Editor ed = prefs(ctx).edit();
        if (TextUtils.isEmpty(id)) ed.remove(KEY_ACTIVE_NOTIF);
        else ed.putString(KEY_ACTIVE_NOTIF, id);
        ed.apply();
    }

    public static long getSnoozedUntil(Context ctx, String id) {
        return readJsonLong(prefs(ctx).getString(KEY_SNOOZED_UNTIL, "{}"), id, 0L);
    }

    public static void snooze(Context ctx, String id, long untilMs) {
        if (!IntentionIds.isValid(id)) return;
        putJsonLong(ctx, KEY_SNOOZED_UNTIL, id, untilMs);
    }

    public static void snoozeFor(Context ctx, String id, long durationMs) {
        snooze(ctx, id, System.currentTimeMillis() + Math.max(0L, durationMs));
    }

    /** Snooze jusqu'à minuit local (ignorer aujourd'hui). */
    public static void ignoreToday(Context ctx, String id) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.add(java.util.Calendar.DAY_OF_YEAR, 1);
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
        cal.set(java.util.Calendar.MINUTE, 0);
        cal.set(java.util.Calendar.SECOND, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        snooze(ctx, id, cal.getTimeInMillis());
    }

    public static int getDailyCount(Context ctx) {
        rollDailyIfNeeded(ctx);
        return prefs(ctx).getInt(KEY_DAILY_COUNT, 0);
    }

    public static void markFired(Context ctx, String id) {
        if (!IntentionIds.isValid(id)) return;
        rollDailyIfNeeded(ctx);
        long now = System.currentTimeMillis();
        SharedPreferences p = prefs(ctx);
        int count = p.getInt(KEY_DAILY_COUNT, 0) + 1;
        putJsonLong(ctx, KEY_LAST_FIRED_BY_ID, id, now);
        p.edit()
                .putLong(KEY_LAST_FIRED_GLOBAL, now)
                .putInt(KEY_DAILY_COUNT, count)
                .putString(KEY_DAILY_COUNT_DATE, today())
                .putString(KEY_ACTIVE_NOTIF, id)
                .apply();
    }

    public static void clearActiveNotification(Context ctx) {
        prefs(ctx).edit().remove(KEY_ACTIVE_NOTIF).apply();
    }

    /** Permet un re-fire après snooze (ex. débrief F1 « Plus tard »). */
    public static void clearLastFired(Context ctx, String id) {
        if (!IntentionIds.isValid(id)) return;
        putJsonLong(ctx, KEY_LAST_FIRED_BY_ID, id, 0L);
    }

    public static boolean firedToday(Context ctx, String id) {
        long last = getLastFired(ctx, id);
        if (last <= 0L) return false;
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTimeInMillis(last);
        String firedDay = String.format(Locale.US, "%04d-%02d-%02d",
                cal.get(java.util.Calendar.YEAR),
                cal.get(java.util.Calendar.MONTH) + 1,
                cal.get(java.util.Calendar.DAY_OF_MONTH));
        return today().equals(firedDay);
    }

    static void rollDailyIfNeeded(Context ctx) {
        String today = today();
        SharedPreferences p = prefs(ctx);
        String stored = p.getString(KEY_DAILY_COUNT_DATE, "");
        if (!today.equals(stored)) {
            p.edit()
                    .putInt(KEY_DAILY_COUNT, 0)
                    .putString(KEY_DAILY_COUNT_DATE, today)
                    .apply();
        }
    }

    private static String today() {
        return LocalDate.now().toString();
    }

    private static int clampHour(int h) {
        return Math.max(0, Math.min(23, h));
    }

    private static long readJsonLong(String raw, String key, long def) {
        if (TextUtils.isEmpty(raw) || TextUtils.isEmpty(key)) return def;
        try {
            JSONObject o = new JSONObject(raw);
            return o.optLong(key, def);
        } catch (Exception e) {
            return def;
        }
    }

    private static void putJsonLong(Context ctx, String prefsKey, String id, long value) {
        SharedPreferences p = prefs(ctx);
        JSONObject o;
        try {
            o = new JSONObject(p.getString(prefsKey, "{}"));
        } catch (Exception e) {
            o = new JSONObject();
        }
        try {
            o.put(id, value);
        } catch (Exception ignored) {}
        p.edit().putString(prefsKey, o.toString()).apply();
    }

    /** Visible tests. */
    public static void clearAll(Context ctx) {
        prefs(ctx).edit().clear().apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
