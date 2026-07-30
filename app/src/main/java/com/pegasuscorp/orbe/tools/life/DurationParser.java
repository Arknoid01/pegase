package com.pegasuscorp.orbe.tools.life;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parse des durées orales françaises → secondes. */
public final class DurationParser {

    private static final Pattern RELATIVE = Pattern.compile(
            "(?i)dans\\s+(\\d+)\\s*(seconde|secondes|sec|s|minute|minutes|min|heure|heures|h)");
    private static final Pattern DURATION = Pattern.compile(
            "(?i)(\\d+)\\s*(seconde|secondes|sec|s|minute|minutes|min|heure|heures|h)"
                    + "(?:\\s*(\\d+)\\s*(?:min|minutes?|sec|secondes?|s)?)?");
    private static final Pattern HM = Pattern.compile("(?i)(\\d+)\\s*h(?:\\s*(\\d{1,2}))?");

    private DurationParser() {}

    /**
     * @return secondes, ou -1 si non reconnu
     */
    public static int parseToSeconds(String raw) {
        if (raw == null || raw.trim().isEmpty()) return -1;
        String s = raw.trim().toLowerCase(Locale.FRENCH);

        Matcher rel = RELATIVE.matcher(s);
        if (rel.find()) {
            return unitToSeconds(Integer.parseInt(rel.group(1)), rel.group(2));
        }

        Matcher hm = HM.matcher(s);
        if (hm.find()) {
            int h = Integer.parseInt(hm.group(1));
            int m = hm.group(2) != null ? Integer.parseInt(hm.group(2)) : 0;
            return h * 3600 + m * 60;
        }

        Matcher dur = DURATION.matcher(s);
        if (dur.find()) {
            int total = unitToSeconds(Integer.parseInt(dur.group(1)), dur.group(2));
            if (dur.group(3) != null && !dur.group(3).isEmpty()) {
                total += Integer.parseInt(dur.group(3)) * 60;
            }
            return total;
        }
        return -1;
    }

    private static int unitToSeconds(int value, String unit) {
        if (unit == null) return value * 60;
        String u = unit.toLowerCase(Locale.ROOT);
        if (u.startsWith("h")) return value * 3600;
        if (u.startsWith("s") || u.equals("sec")) return value;
        return value * 60;
    }
}
