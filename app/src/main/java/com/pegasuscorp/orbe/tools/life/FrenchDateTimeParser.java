package com.pegasuscorp.orbe.tools.life;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parse des dates/heures orales françaises → epoch ms (Europe/Paris).
 */
public final class FrenchDateTimeParser {

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");

    private static final Pattern ISO = Pattern.compile(
            "(\\d{4})-(\\d{2})-(\\d{2})(?:[ T](\\d{1,2})[:hH](\\d{2})?)?");
    private static final Pattern RELATIVE = Pattern.compile(
            "(?i)dans\\s+(\\d+)\\s*(heure|heures|h|minute|minutes|min)");
    private static final Pattern TIME = Pattern.compile(
            "(?i)(?:à\\s*)?(\\d{1,2})\\s*(?:h|:)\\s*(\\d{0,2})");
    private static final Pattern TIME_SIMPLE = Pattern.compile(
            "(?i)(\\d{1,2})\\s*h(?:eures?)?(?:\\s*(\\d{1,2}))?");

    private FrenchDateTimeParser() {}

    /**
     * @return epoch millis, ou -1 si impossible
     */
    public static long parseToEpochMs(String raw) {
        if (raw == null || raw.trim().isEmpty()) return -1;
        String s = raw.trim().toLowerCase(Locale.FRENCH)
                .replace('\u00a0', ' ')
                .replaceAll("\\s+", " ");

        LocalDateTime now = LocalDateTime.now(PARIS);

        Matcher rel = RELATIVE.matcher(s);
        if (rel.find()) {
            int n = Integer.parseInt(rel.group(1));
            String unit = rel.group(2).toLowerCase(Locale.ROOT);
            LocalDateTime t = unit.startsWith("min")
                    ? now.plusMinutes(n)
                    : now.plusHours(n);
            return toEpoch(t);
        }

        Matcher iso = ISO.matcher(s);
        if (iso.find()) {
            int y = Integer.parseInt(iso.group(1));
            int mo = Integer.parseInt(iso.group(2));
            int d = Integer.parseInt(iso.group(3));
            int h = iso.group(4) != null ? Integer.parseInt(iso.group(4)) : 9;
            int mi = iso.group(5) != null && !iso.group(5).isEmpty()
                    ? Integer.parseInt(iso.group(5)) : 0;
            return toEpoch(LocalDateTime.of(y, mo, d, clampHour(h), clampMin(mi)));
        }

        LocalDate date = resolveDay(s, now.toLocalDate());
        LocalTime time = resolveTime(s);
        if (time == null) time = LocalTime.of(9, 0);
        LocalDateTime dt = LocalDateTime.of(date, time);
        // Si « lundi » déjà passé cette semaine et pas de « prochain » → semaine suivante
        if (hasWeekday(s) && dt.isBefore(now) && !s.contains("dernier")) {
            dt = dt.plusWeeks(1);
        }
        // « aujourd'hui 8h » dans le passé → demain
        if ((s.contains("aujourd") || (!hasWeekday(s) && !s.contains("demain")
                && !s.contains("apres-demain") && !s.contains("après-demain")
                && !ISO.matcher(s).find()))
                && dt.isBefore(now) && !s.contains("dans")) {
            // keep if explicit aujourd'hui; otherwise if only time → next occurrence
            if (!s.contains("aujourd") && resolveDay(s, now.toLocalDate()).equals(now.toLocalDate())) {
                dt = dt.plusDays(1);
            }
        }
        return toEpoch(dt);
    }

    public static String formatSpoken(long epochMs) {
        if (epochMs <= 0) return "";
        LocalDateTime dt = LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(epochMs), PARIS);
        LocalDate today = LocalDate.now(PARIS);
        String day;
        if (dt.toLocalDate().equals(today)) day = "aujourd'hui";
        else if (dt.toLocalDate().equals(today.plusDays(1))) day = "demain";
        else {
            day = dt.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRENCH));
        }
        return day + " à " + String.format(Locale.FRENCH, "%dh%02d",
                dt.getHour(), dt.getMinute());
    }

    private static LocalDate resolveDay(String s, LocalDate today) {
        if (s.contains("apres-demain") || s.contains("après-demain")
                || s.contains("apres demain") || s.contains("après demain")) {
            return today.plusDays(2);
        }
        if (s.contains("demain")) return today.plusDays(1);
        if (s.contains("aujourd")) return today;

        DayOfWeek dow = parseWeekday(s);
        if (dow != null) {
            return today.with(TemporalAdjusters.nextOrSame(dow));
        }
        return today;
    }

    private static boolean hasWeekday(String s) {
        return parseWeekday(s) != null;
    }

    private static DayOfWeek parseWeekday(String s) {
        if (s.contains("lundi")) return DayOfWeek.MONDAY;
        if (s.contains("mardi")) return DayOfWeek.TUESDAY;
        if (s.contains("mercredi")) return DayOfWeek.WEDNESDAY;
        if (s.contains("jeudi")) return DayOfWeek.THURSDAY;
        if (s.contains("vendredi")) return DayOfWeek.FRIDAY;
        if (s.contains("samedi")) return DayOfWeek.SATURDAY;
        if (s.contains("dimanche")) return DayOfWeek.SUNDAY;
        return null;
    }

    private static LocalTime resolveTime(String s) {
        Matcher m = TIME.matcher(s);
        if (!m.find()) {
            m = TIME_SIMPLE.matcher(s);
            if (!m.find()) return null;
        }
        int h = Integer.parseInt(m.group(1));
        String minG = m.groupCount() >= 2 ? m.group(2) : null;
        int mi = (minG != null && !minG.isEmpty()) ? Integer.parseInt(minG) : 0;
        return LocalTime.of(clampHour(h), clampMin(mi));
    }

    private static int clampHour(int h) {
        if (h < 0) return 0;
        if (h > 23) return 23;
        return h;
    }

    private static int clampMin(int m) {
        if (m < 0) return 0;
        if (m > 59) return 59;
        return m;
    }

    private static long toEpoch(LocalDateTime dt) {
        return dt.atZone(PARIS).toInstant().toEpochMilli();
    }
}
