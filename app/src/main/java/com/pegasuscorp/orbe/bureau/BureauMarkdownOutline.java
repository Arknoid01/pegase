package com.pegasuscorp.orbe.bureau;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Outline / tâches / insertion par section dans un plan Markdown.
 */
public final class BureauMarkdownOutline {

    private static final Pattern HEADING = Pattern.compile("^(#{1,3})\\s+(.+?)\\s*$");
    private static final Pattern TASK = Pattern.compile("^(\\s*)-\\s+\\[([ xX])\\]\\s*(.*)$");

    public static final class HeadingItem {
        public final int level;
        public final String title;
        public final int lineIndex;
        public final int charOffset;

        public HeadingItem(int level, String title, int lineIndex, int charOffset) {
            this.level = level;
            this.title = title;
            this.lineIndex = lineIndex;
            this.charOffset = charOffset;
        }
    }

    public static final class TaskItem {
        public final boolean done;
        public final String text;
        public final int lineIndex;
        public final int charOffset;

        public TaskItem(boolean done, String text, int lineIndex, int charOffset) {
            this.done = done;
            this.text = text;
            this.lineIndex = lineIndex;
            this.charOffset = charOffset;
        }
    }

    private BureauMarkdownOutline() {}

    public static List<HeadingItem> headings(String markdown) {
        List<HeadingItem> out = new ArrayList<>();
        if (markdown == null) return out;
        String[] lines = markdown.split("\n", -1);
        int offset = 0;
        for (int i = 0; i < lines.length; i++) {
            Matcher m = HEADING.matcher(lines[i]);
            if (m.matches()) {
                out.add(new HeadingItem(m.group(1).length(), m.group(2).trim(), i, offset));
            }
            offset += lines[i].length() + 1;
        }
        return out;
    }

    public static List<TaskItem> tasks(String markdown) {
        List<TaskItem> out = new ArrayList<>();
        if (markdown == null) return out;
        String[] lines = markdown.split("\n", -1);
        int offset = 0;
        for (int i = 0; i < lines.length; i++) {
            Matcher m = TASK.matcher(lines[i]);
            if (m.matches()) {
                boolean done = m.group(2).equalsIgnoreCase("x");
                out.add(new TaskItem(done, m.group(3).trim(), i, offset));
            }
            offset += lines[i].length() + 1;
        }
        return out;
    }

    /** Bascule `- [ ]` ↔ `- [x]` à la ligne donnée. */
    public static String toggleTaskAtLine(String markdown, int lineIndex) {
        if (markdown == null || lineIndex < 0) return markdown == null ? "" : markdown;
        String[] lines = markdown.split("\n", -1);
        if (lineIndex >= lines.length) return markdown;
        Matcher m = TASK.matcher(lines[lineIndex]);
        if (!m.matches()) return markdown;
        boolean done = m.group(2).equalsIgnoreCase("x");
        String indent = m.group(1);
        String text = m.group(3);
        lines[lineIndex] = indent + "- [" + (done ? " " : "x") + "] " + text;
        return String.join("\n", lines);
    }

    /**
     * Insère {@code content} juste après le heading {@code ## sectionTitle}
     * (crée la section en fin de doc si absente).
     */
    public static String insertUnderSection(String markdown, String sectionTitle, String content) {
        String doc = markdown == null ? "" : markdown;
        String addition = content == null ? "" : content.trim();
        if (addition.isEmpty()) return doc;

        String target = fold(sectionTitle);
        String[] lines = doc.split("\n", -1);
        int insertAfter = -1;
        for (int i = 0; i < lines.length; i++) {
            Matcher m = HEADING.matcher(lines[i]);
            if (m.matches() && fold(m.group(2)).equals(target)) {
                insertAfter = i;
                break;
            }
        }
        if (insertAfter < 0) {
            StringBuilder sb = new StringBuilder(doc.trim());
            if (sb.length() > 0) sb.append("\n\n");
            sb.append("## ").append(sectionTitle).append("\n\n").append(addition).append("\n");
            return sb.toString();
        }

        int end = lines.length;
        for (int i = insertAfter + 1; i < lines.length; i++) {
            Matcher m = HEADING.matcher(lines[i]);
            if (m.matches() && m.group(1).length() <= 2) {
                end = i;
                break;
            }
        }

        List<String> out = new ArrayList<>();
        for (int i = 0; i <= insertAfter; i++) out.add(lines[i]);
        // Skip blank line right after heading for cleanliness
        int bodyStart = insertAfter + 1;
        if (bodyStart < end && lines[bodyStart].trim().isEmpty()) {
            out.add("");
            bodyStart++;
        } else {
            out.add("");
        }
        for (String addLine : addition.split("\n", -1)) {
            out.add(addLine);
        }
        out.add("");
        for (int i = bodyStart; i < end; i++) out.add(lines[i]);
        for (int i = end; i < lines.length; i++) out.add(lines[i]);
        return String.join("\n", out);
    }

    /** Append une ligne sous Historique Pégase. */
    public static String appendHistorique(String markdown, String note) {
        String line = "- " + (note == null ? "" : note.trim());
        if (line.equals("- ")) return markdown == null ? "" : markdown;
        return insertUnderSection(markdown, BureauPlanTemplate.SECTION_HISTORIQUE, line);
    }

    private static String fold(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT)
                .replace('é', 'e').replace('è', 'e').replace('ê', 'e')
                .replace('à', 'a')
                .trim();
    }
}
