package com.pegasuscorp.orbe.inbox;

import java.util.Calendar;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Convertit les libellés d'heure de WhatsApp Web et Messenger en date approximative,
 * pour trier toutes les sources ensemble. Pur (heure courante passée en paramètre).
 *
 * WhatsApp : « 20:42 », « Hier », « jeudi », « 05/10/2026 ».
 * Messenger : « 12 min », « 2 h », « 1 j », « 3 sem. », « 1 an ».
 */
public final class InboxTimeParser {

    private static final Pattern CLOCK = Pattern.compile("^(\\d{1,2}):(\\d{2})$");
    private static final Pattern DATE = Pattern.compile("^(\\d{1,2})/(\\d{1,2})/(\\d{2,4})$");
    private static final Pattern RELATIVE = Pattern.compile(
            "^(\\d+)\\s*(min|h|j|sem\\.?|mois|an|ans)$");
    private static final String[] WEEKDAYS = {
            "dimanche", "lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi"};

    private InboxTimeParser() {}

    /** @return ms epoch, ou 0 si le libellé n'est pas reconnu. */
    public static long parse(String label, long nowMs) {
        if (label == null) return 0L;
        String t = label.trim().toLowerCase(Locale.FRENCH);
        if (t.isEmpty()) return 0L;
        Calendar cal = Calendar.getInstance(Locale.FRENCH);
        cal.setTimeInMillis(nowMs);

        if (t.equals("à l'instant") || t.equals("a l'instant") || t.equals("maintenant")) {
            return nowMs;
        }
        Matcher m = CLOCK.matcher(t);
        if (m.matches()) {
            cal.set(Calendar.HOUR_OF_DAY, Integer.parseInt(m.group(1)));
            cal.set(Calendar.MINUTE, Integer.parseInt(m.group(2)));
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            return cal.getTimeInMillis();
        }
        if (t.equals("hier")) {
            startOfDay(cal);
            cal.add(Calendar.DAY_OF_MONTH, -1);
            return cal.getTimeInMillis();
        }
        for (int i = 0; i < WEEKDAYS.length; i++) {
            if (!t.equals(WEEKDAYS[i])) continue;
            startOfDay(cal);
            int today = cal.get(Calendar.DAY_OF_WEEK) - 1;
            int back = (today - i + 7) % 7;
            cal.add(Calendar.DAY_OF_MONTH, -(back == 0 ? 7 : back));
            return cal.getTimeInMillis();
        }
        m = DATE.matcher(t);
        if (m.matches()) {
            int year = Integer.parseInt(m.group(3));
            if (year < 100) year += 2000;
            startOfDay(cal);
            cal.set(year, Integer.parseInt(m.group(2)) - 1, Integer.parseInt(m.group(1)));
            return cal.getTimeInMillis();
        }
        m = RELATIVE.matcher(t);
        if (m.matches()) {
            long n = Long.parseLong(m.group(1));
            String unit = m.group(2);
            long minute = 60_000L;
            long day = 24 * 60 * minute;
            long delta;
            if (unit.equals("min")) delta = n * minute;
            else if (unit.equals("h")) delta = n * 60 * minute;
            else if (unit.equals("j")) delta = n * day;
            else if (unit.startsWith("sem")) delta = n * 7 * day;
            else if (unit.equals("mois")) delta = n * 30 * day;
            else delta = n * 365 * day;
            return nowMs - delta;
        }
        return 0L;
    }

    private static void startOfDay(Calendar cal) {
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
    }
}
