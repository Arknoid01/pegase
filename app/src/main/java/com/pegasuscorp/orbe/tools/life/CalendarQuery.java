package com.pegasuscorp.orbe.tools.life;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;
import android.text.TextUtils;
import android.util.Log;

import com.pegasuscorp.orbe.permissions.PermissionFlow;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Lecture et écriture calendrier Android (READ/WRITE_CALENDAR). */
public final class CalendarQuery {

    private static final String TAG = "CalendarQuery";
    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");

    public static final class Event {
        public final long eventId;
        public final String title;
        public final long beginMs;
        public final long endMs;
        public final String location;

        public Event(long eventId, String title, long beginMs, long endMs, String location) {
            this.eventId = eventId;
            this.title = title == null || title.isEmpty() ? "Sans titre" : title;
            this.beginMs = beginMs;
            this.endMs = endMs;
            this.location = location == null ? "" : location;
        }
    }

    private CalendarQuery() {}

    public static List<Event> eventsBetween(Context ctx, long startMs, long endMs) {
        if (ctx == null || !PermissionFlow.hasCalendar(ctx)) return Collections.emptyList();
        ContentResolver cr = ctx.getContentResolver();
        String[] projection = new String[]{
                CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.EVENT_LOCATION,
                CalendarContract.Instances.ALL_DAY
        };
        List<Event> out = new ArrayList<>();
        try (Cursor c = CalendarContract.Instances.query(cr, projection, startMs, endMs)) {
            if (c == null) return out;
            while (c.moveToNext()) {
                if (c.getInt(5) == 1) continue;
                long begin = c.getLong(2);
                long end = c.getLong(3);
                if (begin >= endMs || end <= startMs) continue;
                out.add(new Event(
                        c.getLong(0),
                        c.getString(1),
                        begin,
                        end,
                        c.getString(4)));
            }
        } catch (SecurityException e) {
            Log.w(TAG, "permission", e);
        } catch (Exception e) {
            Log.w(TAG, "eventsBetween", e);
        }
        out.sort((a, b) -> Long.compare(a.beginMs, b.beginMs));
        return out;
    }

    public static List<Event> today(Context ctx) {
        return dayRange(ctx, LocalDate.now(PARIS));
    }

    public static List<Event> tomorrow(Context ctx) {
        return dayRange(ctx, LocalDate.now(PARIS).plusDays(1));
    }

    public static List<Event> week(Context ctx) {
        ZonedDateTime start = LocalDate.now(PARIS).atStartOfDay(PARIS);
        ZonedDateTime end = start.plusDays(7);
        return eventsBetween(ctx, start.toInstant().toEpochMilli(), end.toInstant().toEpochMilli());
    }

    public static List<Event> dayRange(Context ctx, LocalDate day) {
        ZonedDateTime start = day.atStartOfDay(PARIS);
        ZonedDateTime end = start.plusDays(1);
        return eventsBetween(ctx, start.toInstant().toEpochMilli(), end.toInstant().toEpochMilli());
    }

    public static String formatEventLine(Event event) {
        return formatTime(event.beginMs) + " — " + event.title
                + (TextUtils.isEmpty(event.location) ? "" : " (" + event.location + ")");
    }

    public static String formatTime(long beginMs) {
        ZonedDateTime dt = Instant.ofEpochMilli(beginMs).atZone(PARIS);
        return String.format(Locale.FRANCE, "%02d:%02d", dt.getHour(), dt.getMinute());
    }

    public static String formatDayLabel(long beginMs) {
        LocalDate day = Instant.ofEpochMilli(beginMs).atZone(PARIS).toLocalDate();
        LocalDate today = LocalDate.now(PARIS);
        if (day.equals(today)) return "aujourd'hui";
        if (day.equals(today.plusDays(1))) return "demain";
        return day.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRENCH));
    }

    public static String summarizeList(List<Event> events, String emptyMessage) {
        if (events == null || events.isEmpty()) return emptyMessage;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < events.size(); i++) {
            Event e = events.get(i);
            if (i > 0) sb.append("\n");
            sb.append("• ").append(formatDayLabel(e.beginMs)).append(" ")
                    .append(formatEventLine(e));
        }
        return sb.toString();
    }

    public static boolean deleteEvent(Context ctx, long eventId) {
        if (ctx == null || eventId <= 0 || !PermissionFlow.hasCalendar(ctx)) return false;
        try {
            Uri uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId);
            int rows = ctx.getContentResolver().delete(uri, null, null);
            return rows > 0;
        } catch (SecurityException e) {
            Log.w(TAG, "delete permission", e);
            return false;
        } catch (Exception e) {
            Log.w(TAG, "deleteEvent", e);
            return false;
        }
    }

    public static Event findByTitle(Context ctx, String title, long startMs, long endMs) {
        if (TextUtils.isEmpty(title)) return null;
        String needle = title.trim().toLowerCase(Locale.ROOT);
        for (Event event : eventsBetween(ctx, startMs, endMs)) {
            if (event.title.toLowerCase(Locale.ROOT).contains(needle)) return event;
        }
        return null;
    }
}
