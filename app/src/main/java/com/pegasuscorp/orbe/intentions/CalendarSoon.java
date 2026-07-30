package com.pegasuscorp.orbe.intentions;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.provider.CalendarContract;
import android.text.TextUtils;
import android.util.Log;

import com.pegasuscorp.orbe.permissions.PermissionFlow;

import java.util.Locale;

/**
 * Prochain événement calendrier dans une fenêtre (local, READ_CALENDAR).
 */
public final class CalendarSoon {

    private static final String TAG = "CalendarSoon";

    /** Fenêtre : entre 10 min et 45 min. */
    public static final long MIN_AHEAD_MS = 10L * 60L * 1000L;
    public static final long MAX_AHEAD_MS = 45L * 60L * 1000L;

    public static final class Event {
        public final long id;
        public final String title;
        public final long beginMs;

        public Event(long id, String title, long beginMs) {
            this.id = id;
            this.title = title == null ? "RDV" : title;
            this.beginMs = beginMs;
        }

        public String intentionId() {
            return "calendar:" + id;
        }

        public String minutesLabel(long nowMs) {
            long mins = Math.max(1, (beginMs - nowMs) / 60_000L);
            return String.valueOf(mins);
        }
    }

    private CalendarSoon() {}

    public static Event nextSoon(Context ctx, long nowMs) {
        if (ctx == null || !PermissionFlow.hasCalendar(ctx)) return null;
        ContentResolver cr = ctx.getContentResolver();
        long start = nowMs + MIN_AHEAD_MS;
        long end = nowMs + MAX_AHEAD_MS;
        String[] projection = new String[]{
                CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.ALL_DAY
        };
        try (Cursor c = CalendarContract.Instances.query(
                cr, projection, start, end)) {
            if (c == null) return null;
            Event best = null;
            while (c.moveToNext()) {
                if (c.getInt(3) == 1) continue; // ignore all-day
                long begin = c.getLong(2);
                if (begin < start || begin > end) continue;
                long id = c.getLong(0);
                String title = c.getString(1);
                if (TextUtils.isEmpty(title)) title = "RDV";
                if (best == null || begin < best.beginMs) {
                    best = new Event(id, title.trim(), begin);
                }
            }
            return best;
        } catch (SecurityException e) {
            Log.w(TAG, "calendar permission", e);
            return null;
        } catch (Exception e) {
            Log.w(TAG, "nextSoon", e);
            return null;
        }
    }

    public static String formatTime(long beginMs) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTimeInMillis(beginMs);
        return String.format(Locale.FRANCE, "%02d:%02d",
                cal.get(java.util.Calendar.HOUR_OF_DAY),
                cal.get(java.util.Calendar.MINUTE));
    }
}
