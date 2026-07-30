package com.pegasuscorp.orbe.tools.device;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.provider.CalendarContract;
import android.util.Log;

import com.pegasuscorp.orbe.permissions.PermissionFlow;

import java.util.TimeZone;

/** Insertion calendrier silencieuse quand WRITE_CALENDAR est accordé. */
public final class CalendarWriter {

    private static final String TAG = "CalendarWriter";

    private CalendarWriter() {}

    public static long insertEvent(Context ctx, String title, long startMs, long endMs,
            String description, String location, int reminderMin) {
        if (ctx == null || !PermissionFlow.hasCalendar(ctx)) return -1;
        long calendarId = defaultCalendarId(ctx.getContentResolver());
        if (calendarId < 0) return -1;

        ContentValues values = new ContentValues();
        values.put(CalendarContract.Events.CALENDAR_ID, calendarId);
        values.put(CalendarContract.Events.DTSTART, startMs);
        values.put(CalendarContract.Events.DTEND, endMs);
        values.put(CalendarContract.Events.TITLE, title);
        values.put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().getID());
        values.put(CalendarContract.Events.HAS_ALARM, 1);
        if (description != null && !description.isEmpty()) {
            values.put(CalendarContract.Events.DESCRIPTION, description);
        }
        if (location != null && !location.isEmpty()) {
            values.put(CalendarContract.Events.EVENT_LOCATION, location);
        }

        try {
            Uri uri = ctx.getContentResolver().insert(CalendarContract.Events.CONTENT_URI, values);
            if (uri == null) return -1;
            long eventId = Long.parseLong(uri.getLastPathSegment());
            if (reminderMin >= 0) {
                insertReminder(ctx.getContentResolver(), eventId, reminderMin);
            }
            return eventId;
        } catch (SecurityException e) {
            Log.w(TAG, "insert permission", e);
            return -1;
        } catch (Exception e) {
            Log.w(TAG, "insertEvent", e);
            return -1;
        }
    }

    private static void insertReminder(ContentResolver cr, long eventId, int minutes) {
        ContentValues values = new ContentValues();
        values.put(CalendarContract.Reminders.EVENT_ID, eventId);
        values.put(CalendarContract.Reminders.MINUTES, minutes);
        values.put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT);
        try {
            cr.insert(CalendarContract.Reminders.CONTENT_URI, values);
        } catch (Exception e) {
            Log.w(TAG, "insertReminder", e);
        }
    }

    private static long defaultCalendarId(ContentResolver cr) {
        String[] projection = new String[]{
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.IS_PRIMARY
        };
        try (android.database.Cursor c = cr.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                CalendarContract.Calendars.VISIBLE + "=1",
                null,
                null)) {
            if (c == null) return -1;
            long fallback = -1;
            while (c.moveToNext()) {
                long id = c.getLong(0);
                if (fallback < 0) fallback = id;
                if (c.getInt(1) == 1) return id;
            }
            return fallback;
        } catch (Exception e) {
            Log.w(TAG, "defaultCalendarId", e);
            return -1;
        }
    }
}
