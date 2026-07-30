package com.pegasuscorp.orbe.bureau;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rendu HTML de l'aperçu bureau — titres, listes, tableaux Markdown, blocs Mermaid.
 */
public final class BureauMarkdownHtml {

    private static final Pattern HEADING = Pattern.compile("^(#{1,3})\\s+(.+)$");
    private static final Pattern TASK = Pattern.compile("^-\\s+\\[([ xX])\\]\\s+(.+)$");
    private static final Pattern BULLET = Pattern.compile("^-\\s+(.+)$");
    private static final Pattern FENCE_OPEN = Pattern.compile("^```(\\w*)\\s*$");
    private static final String MERMAID_CDN =
            "https://cdn.jsdelivr.net/npm/mermaid@10/dist/mermaid.min.js";

    private BureauMarkdownHtml() {}

    public static String toHtml(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return wrap("<p><em>Document vide</em></p>");
        }
        StringBuilder body = new StringBuilder();
        boolean inList = false;
        boolean inFence = false;
        boolean mermaidFence = false;
        StringBuilder fenceBuf = new StringBuilder();
        List<String> tableRows = new ArrayList<>();

        String[] lines = markdown.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            if (inFence) {
                if (trimmed.startsWith("```")) {
                    flushFence(body, fenceBuf, mermaidFence);
                    fenceBuf.setLength(0);
                    inFence = false;
                    mermaidFence = false;
                } else {
                    if (fenceBuf.length() > 0) fenceBuf.append('\n');
                    fenceBuf.append(line);
                }
                continue;
            }

            Matcher fence = FENCE_OPEN.matcher(trimmed);
            if (fence.matches()) {
                if (inList) { body.append("</ul>\n"); inList = false; }
                flushTable(body, tableRows);
                inFence = true;
                String lang = fence.group(1) == null ? "" : fence.group(1).toLowerCase(Locale.ROOT);
                mermaidFence = "mermaid".equals(lang);
                fenceBuf.setLength(0);
                continue;
            }

            if (isTableRow(trimmed)) {
                if (inList) { body.append("</ul>\n"); inList = false; }
                tableRows.add(trimmed);
                continue;
            }
            if (!tableRows.isEmpty()) {
                flushTable(body, tableRows);
            }

            if (trimmed.isEmpty()) {
                if (inList) {
                    body.append("</ul>\n");
                    inList = false;
                }
                continue;
            }
            Matcher h = HEADING.matcher(trimmed);
            if (h.matches()) {
                if (inList) { body.append("</ul>\n"); inList = false; }
                int level = h.group(1).length();
                body.append('<').append('h').append(level).append('>')
                        .append(inline(h.group(2))).append("</h").append(level).append(">\n");
                continue;
            }
            Matcher task = TASK.matcher(trimmed);
            if (task.matches()) {
                if (!inList) { body.append("<ul>\n"); inList = true; }
                boolean done = task.group(1).toLowerCase().charAt(0) == 'x';
                body.append("<li><input type=\"checkbox\" disabled")
                        .append(done ? " checked" : "").append("> ")
                        .append(inline(task.group(2))).append("</li>\n");
                continue;
            }
            Matcher bullet = BULLET.matcher(trimmed);
            if (bullet.matches()) {
                if (!inList) { body.append("<ul>\n"); inList = true; }
                body.append("<li>").append(inline(bullet.group(1))).append("</li>\n");
                continue;
            }
            if (inList) { body.append("</ul>\n"); inList = false; }
            body.append("<p>").append(inline(trimmed)).append("</p>\n");
        }
        if (inFence) {
            flushFence(body, fenceBuf, mermaidFence);
        }
        flushTable(body, tableRows);
        if (inList) body.append("</ul>\n");
        return wrap(body.toString());
    }

    private static boolean isTableRow(String trimmed) {
        if (trimmed == null || trimmed.length() < 2) return false;
        if (!trimmed.startsWith("|")) return false;
        // Au moins un | intérieur (séparateur ou cellule)
        return trimmed.indexOf('|', 1) >= 0;
    }

    private static boolean isTableSeparator(String row) {
        String t = row.replace("|", "").replace(":", "").replace("-", "").replace(" ", "");
        return t.isEmpty() && row.contains("-");
    }

    private static void flushTable(StringBuilder body, List<String> rows) {
        if (rows == null || rows.isEmpty()) return;
        List<String[]> cells = new ArrayList<>();
        for (String row : rows) {
            if (isTableSeparator(row)) continue;
            cells.add(splitTableCells(row));
        }
        rows.clear();
        if (cells.isEmpty()) return;

        body.append("<table>\n<thead><tr>");
        for (String c : cells.get(0)) {
            body.append("<th>").append(inline(c)).append("</th>");
        }
        body.append("</tr></thead>\n");
        if (cells.size() > 1) {
            body.append("<tbody>\n");
            for (int r = 1; r < cells.size(); r++) {
                body.append("<tr>");
                for (String c : cells.get(r)) {
                    body.append("<td>").append(inline(c)).append("</td>");
                }
                body.append("</tr>\n");
            }
            body.append("</tbody>\n");
        }
        body.append("</table>\n");
    }

    static String[] splitTableCells(String row) {
        String t = row.trim();
        if (t.startsWith("|")) t = t.substring(1);
        if (t.endsWith("|")) t = t.substring(0, t.length() - 1);
        String[] parts = t.split("\\|", -1);
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].trim();
        }
        return parts;
    }

    private static void flushFence(StringBuilder body, StringBuilder fenceBuf, boolean mermaid) {
        String raw = fenceBuf.toString();
        if (mermaid) {
            body.append("<div class=\"mermaid\">\n")
                    .append(escapeMermaid(raw))
                    .append("\n</div>\n");
        } else {
            body.append("<pre><code>")
                    .append(escape(raw))
                    .append("</code></pre>\n");
        }
    }

    /** Mermaid parse le texte ; on échappe seulement &lt; pour éviter du HTML cassé. */
    private static String escapeMermaid(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;");
    }

    private static String inline(String s) {
        String escaped = escape(s);
        return escaped.replaceAll("\\*\\*(.+?)\\*\\*", "<strong>$1</strong>");
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private static String wrap(String body) {
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"/>"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"/>"
                + "<style>"
                + "html,body{background:#141A22;color:#E8EEF7;}"
                + "body{font-family:sans-serif;"
                + "padding:14px;line-height:1.45;margin:0;}"
                + "h1,h2,h3{color:#5B8DEF;margin:1em 0 .4em;}"
                + "ul{padding-left:1.2em;} li{margin:.35em 0;}"
                + "p{margin:.5em 0;} em{color:#8B9BB4;}"
                + "pre{background:#0B0E14;padding:10px;border-radius:8px;overflow:auto;}"
                + "code{font-family:monospace;font-size:.9em;}"
                + "table{border-collapse:collapse;width:100%;margin:.8em 0;font-size:.92em;}"
                + "th,td{border:1px solid #2A3544;padding:8px 10px;text-align:left;}"
                + "th{background:#1A2330;color:#5B8DEF;}"
                + "tr:nth-child(even) td{background:#101820;}"
                + ".mermaid{background:#0B0E14;padding:12px;border-radius:8px;margin:.8em 0;"
                + "overflow:auto;}"
                + "</style>"
                + "<script src=\"" + MERMAID_CDN + "\"></script>"
                + "<script>mermaid.initialize({startOnLoad:true,theme:'dark',"
                + "securityLevel:'loose'});</script>"
                + "</head><body>" + body + "</body></html>";
    }
}
