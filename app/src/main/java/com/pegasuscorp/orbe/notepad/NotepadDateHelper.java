package com.pegasuscorp.orbe.notepad;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parse les dates françaises pour le bloc-notes (demain, lundi, à 14h…).
 */
public final class NotepadDateHelper {

    private static final Pattern TIME_AT = Pattern.compile(
            "(?i)(?:à|a)\\s*(\\d{1,2})\\s*(?:h|heures?)(?:\\s*(\\d{2}))?");
    private static final Pattern IN_HOURS = Pattern.compile(
            "(?i)dans\\s+(\\d{1,2})\\s*(?:heure|heures|h)");

    private NotepadDateHelper() {}

    public static String today() {
        return formatDate(new Date());
    }

    public static String tomorrow() {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_YEAR, 1);
        return formatDate(c.getTime());
    }

    public static String dayAfterTomorrow() {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_YEAR, 2);
        return formatDate(c.getTime());
    }

    public static String parseDueDate(String fold) {
        if (fold.contains("apres demain") || fold.contains("après demain")) {
            return dayAfterTomorrow();
        }
        if (fold.contains("demain")) return tomorrow();
        if (fold.contains("aujourd") || fold.contains("ce soir") || fold.contains("ce matin")) {
            return today();
        }
        String[] days = {"lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi", "dimanche"};
        for (int i = 0; i < days.length; i++) {
            if (fold.contains(days[i])) {
                return nextWeekday(i + Calendar.MONDAY);
            }
        }
        return "";
    }

    public static long parseReminderAtMillis(String text, String fold) {
        Matcher m = TIME_AT.matcher(text);
        long base = System.currentTimeMillis();
        Calendar cal = Calendar.getInstance();
        if (fold.contains("demain")) {
            cal.add(Calendar.DAY_OF_YEAR, 1);
        }
        if (m.find()) {
            int hour = Integer.parseInt(m.group(1));
            int minute = m.group(2) != null ? Integer.parseInt(m.group(2)) : 0;
            cal.set(Calendar.HOUR_OF_DAY, hour);
            cal.set(Calendar.MINUTE, minute);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            if (cal.getTimeInMillis() <= base) cal.add(Calendar.DAY_OF_YEAR, 1);
            return cal.getTimeInMillis();
        }
        m = IN_HOURS.matcher(fold);
        if (m.find()) {
            int hours = Integer.parseInt(m.group(1));
            return base + hours * 3600_000L;
        }
        if (fold.contains("dans une heure") || fold.contains("dans 1 heure")) {
            return base + 3600_000L;
        }
        if (fold.contains("dans 30 minutes") || fold.contains("dans une demi heure")) {
            return base + 30 * 60_000L;
        }
        return 0;
    }

    public static int parsePriority(String fold) {
        if (fold.contains("urgent") || fold.contains("tres important")
                || fold.contains("très important")) return 2;
        if (fold.contains("prioritaire") || fold.contains("important")) return 1;
        return 0;
    }

    public static String priorityLabel(int priority) {
        if (priority >= 2) return "urgent";
        if (priority == 1) return "important";
        return "";
    }

    public static String formatDateLabel(String yyyyMmDd) {
        if (yyyyMmDd == null || yyyyMmDd.isEmpty()) return "";
        if (yyyyMmDd.equals(today())) return "aujourd'hui";
        if (yyyyMmDd.equals(tomorrow())) return "demain";
        return yyyyMmDd;
    }

    private static String nextWeekday(int targetDay) {
        Calendar c = Calendar.getInstance();
        int today = c.get(Calendar.DAY_OF_WEEK);
        int delta = targetDay - today;
        if (delta <= 0) delta += 7;
        c.add(Calendar.DAY_OF_YEAR, delta);
        return formatDate(c.getTime());
    }

    private static String formatDate(Date date) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.FRANCE).format(date);
    }
}
