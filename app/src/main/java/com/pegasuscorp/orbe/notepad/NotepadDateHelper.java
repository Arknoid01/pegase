package com.pegasuscorp.orbe.notepad;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parse les dates françaises pour le bloc-notes (demain, lundi, à 14h…).
 * Défauts rappel figés : sans jour/heure → +1 h ; jour sans heure → 9 h.
 */
public final class NotepadDateHelper {

    /** Heure de fire par défaut quand un jour est connu sans heure (ex. « demain »). */
    public static final int DEFAULT_REMINDER_HOUR = 9;

    /** Décalage par défaut si rappel sans jour ni heure. */
    public static final long DEFAULT_REMINDER_OFFSET_MS = 3_600_000L;

    private static final Pattern TIME_AT = Pattern.compile(
            "(?i)(?:à|a)\\s*(\\d{1,2})\\s*(?:h|heures?)(?:\\s*(\\d{2}))?");
    private static final Pattern IN_HOURS = Pattern.compile(
            "(?i)dans\\s+(\\d{1,2})\\s*(?:heure|heures|h)");

    /** « 23 novembre », « 1er décembre 2026 » (avec ou sans accents). */
    private static final Pattern DAY_MONTH = Pattern.compile(
            "(?i)\\b(\\d{1,2})(?:er)?\\s+(janvier|f[eé]vrier|mars|avril|mai|juin|juillet|ao[uû]t"
            + "|septembre|octobre|novembre|d[eé]cembre)(?:\\s+(\\d{4}))?");
    private static final Pattern ISO_DATE = Pattern.compile("\\b(\\d{4})-(\\d{2})-(\\d{2})\\b");

    private NotepadDateHelper() {}

    /**
     * Date explicite dans le texte (« lundi 23 novembre 2026 », « 2026-11-23 »), sinon null.
     * Sans année : l'année en cours, ou la suivante si la date est passée depuis plus d'un jour.
     */
    public static java.time.LocalDate parseExplicitDate(String text) {
        if (text == null || text.isEmpty()) return null;
        try {
            Matcher iso = ISO_DATE.matcher(text);
            if (iso.find()) {
                return java.time.LocalDate.of(Integer.parseInt(iso.group(1)),
                        Integer.parseInt(iso.group(2)), Integer.parseInt(iso.group(3)));
            }
            Matcher m = DAY_MONTH.matcher(text);
            if (!m.find()) return null;
            int day = Integer.parseInt(m.group(1));
            int month = monthNumber(m.group(2).toLowerCase(Locale.FRENCH));
            if (month < 1 || day < 1 || day > 31) return null;
            java.time.LocalDate today = java.time.LocalDate.now();
            if (m.group(3) != null) {
                return java.time.LocalDate.of(Integer.parseInt(m.group(3)), month, day);
            }
            java.time.LocalDate d = java.time.LocalDate.of(today.getYear(), month, day);
            if (d.isBefore(today.minusDays(1))) d = d.plusYears(1);
            return d;
        } catch (Exception e) {
            return null;
        }
    }

    private static int monthNumber(String m) {
        if (m.startsWith("janv")) return 1;
        if (m.startsWith("f")) return 2;
        if (m.startsWith("mars")) return 3;
        if (m.startsWith("avr")) return 4;
        if (m.startsWith("mai")) return 5;
        if (m.equals("juin")) return 6;
        if (m.startsWith("juil")) return 7;
        if (m.startsWith("ao")) return 8;
        if (m.startsWith("sept")) return 9;
        if (m.startsWith("oct")) return 10;
        if (m.startsWith("nov")) return 11;
        if (m.startsWith("d")) return 12;
        return -1;
    }

    /** Résolution dueDate + reminderAt (+ éventuel défaut annoncé). */
    public static final class ReminderResolution {
        public final String dueDate;
        public final long reminderAt;
        public final boolean appliedDefault;
        /** Ex. « dans une heure », « demain à 9 heures » — vide si pas de rappel. */
        public final String spokenWhen;

        public ReminderResolution(String dueDate, long reminderAt,
                boolean appliedDefault, String spokenWhen) {
            this.dueDate = dueDate != null ? dueDate : "";
            this.reminderAt = reminderAt;
            this.appliedDefault = appliedDefault;
            this.spokenWhen = spokenWhen != null ? spokenWhen : "";
        }
    }

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
        if (fold == null || fold.isEmpty()) return "";
        java.time.LocalDate explicit = parseExplicitDate(fold);
        if (explicit != null) return explicit.toString();
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
        if (text == null) text = "";
        if (fold == null) fold = "";
        Matcher m = TIME_AT.matcher(text);
        long base = System.currentTimeMillis();
        Calendar cal = Calendar.getInstance();
        if (fold.contains("demain")) {
            cal.add(Calendar.DAY_OF_YEAR, 1);
        } else if (fold.contains("apres demain") || fold.contains("après demain")) {
            cal.add(Calendar.DAY_OF_YEAR, 2);
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

    /**
     * Applique les défauts rappel : jour sans heure → 9 h ; rappel sans jour → +1 h.
     *
     * @param forceReminder true pour « rappelle-moi » (même sans jour/heure)
     */
    public static ReminderResolution resolveReminder(String text, String fold,
            String dueDateHint, long reminderAtHint, boolean forceReminder) {
        String due = dueDateHint != null ? dueDateHint.trim() : "";
        if (due.isEmpty()) due = parseDueDate(fold);
        long rem = reminderAtHint > 0
                ? reminderAtHint
                : parseReminderAtMillis(text != null ? text : "", fold != null ? fold : "");

        boolean wantsReminder = forceReminder
                || rem > 0
                || !due.isEmpty()
                || (fold != null && fold.contains("rappelle"));

        if (rem > 0) {
            if (due.isEmpty() && fold != null && fold.contains("demain")) {
                due = tomorrow();
            }
            if (reminderAtHint <= 0 && !due.isEmpty()) {
                // L'heure vient du texte : le jour aussi (« lundi 23 novembre à 18h »)
                rem = alignToDate(rem, due);
                if (rem <= System.currentTimeMillis()) due = formatDate(new Date(rem));
            }
            return new ReminderResolution(due, rem, false, formatSpokenWhen(rem, due));
        }

        if (!wantsReminder) {
            return new ReminderResolution(due, 0, false, "");
        }

        if (!due.isEmpty()) {
            rem = millisAtHourOnDate(due, DEFAULT_REMINDER_HOUR);
            String spoken = formatDateLabel(due) + " à " + DEFAULT_REMINDER_HOUR + " heures";
            return new ReminderResolution(due, rem, true, spoken);
        }

        rem = System.currentTimeMillis() + DEFAULT_REMINDER_OFFSET_MS;
        return new ReminderResolution("", rem, true, "dans une heure");
    }

    /** Garde l'heure de {@code ms} mais au jour {@code yyyyMmDd} ; si passé, {@code ms} inchangé. */
    static long alignToDate(long ms, String yyyyMmDd) {
        try {
            Date d = new SimpleDateFormat("yyyy-MM-dd", Locale.FRANCE).parse(yyyyMmDd);
            if (d == null) return ms;
            Calendar src = Calendar.getInstance();
            src.setTimeInMillis(ms);
            Calendar cal = Calendar.getInstance();
            cal.setTime(d);
            cal.set(Calendar.HOUR_OF_DAY, src.get(Calendar.HOUR_OF_DAY));
            cal.set(Calendar.MINUTE, src.get(Calendar.MINUTE));
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            long out = cal.getTimeInMillis();
            return out > System.currentTimeMillis() ? out : ms;
        } catch (Exception e) {
            return ms;
        }
    }

    public static long millisAtHourOnDate(String yyyyMmDd, int hourOfDay) {
        if (yyyyMmDd == null || yyyyMmDd.isEmpty()) {
            return System.currentTimeMillis() + DEFAULT_REMINDER_OFFSET_MS;
        }
        try {
            Date d = new SimpleDateFormat("yyyy-MM-dd", Locale.FRANCE).parse(yyyyMmDd);
            Calendar cal = Calendar.getInstance();
            if (d != null) cal.setTime(d);
            cal.set(Calendar.HOUR_OF_DAY, hourOfDay);
            cal.set(Calendar.MINUTE, 0);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            long ms = cal.getTimeInMillis();
            if (ms <= System.currentTimeMillis()) {
                return System.currentTimeMillis() + DEFAULT_REMINDER_OFFSET_MS;
            }
            return ms;
        } catch (Exception e) {
            return System.currentTimeMillis() + DEFAULT_REMINDER_OFFSET_MS;
        }
    }

    public static String formatSpokenWhen(long reminderAt, String dueDate) {
        if (reminderAt <= 0) return "";
        long now = System.currentTimeMillis();
        long delta = reminderAt - now;
        if (delta > 50 * 60_000L && delta < 70 * 60_000L) {
            return "dans une heure";
        }
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(reminderAt);
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        int minute = cal.get(Calendar.MINUTE);
        String day = formatDateLabel(dueDate != null && !dueDate.isEmpty()
                ? dueDate
                : formatDate(cal.getTime()));
        if (minute == 0) {
            return (day.isEmpty() ? "" : day + " à ") + hour + " heures";
        }
        String mm = minute < 10 ? "0" + minute : String.valueOf(minute);
        return (day.isEmpty() ? "" : day + " à ") + hour + " h " + mm;
    }

    public static int parsePriority(String fold) {
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
        if (yyyyMmDd.equals(dayAfterTomorrow())) return "après-demain";
        try {
            java.time.LocalDate d = java.time.LocalDate.parse(yyyyMmDd);
            String label = d.format(java.time.format.DateTimeFormatter.ofPattern(
                    "EEEE d MMMM", Locale.FRENCH));
            if (d.getYear() != java.time.LocalDate.now().getYear()) label += " " + d.getYear();
            return label;
        } catch (Exception e) {
            return yyyyMmDd;
        }
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
