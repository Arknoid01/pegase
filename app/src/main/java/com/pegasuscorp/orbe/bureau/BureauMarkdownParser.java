package com.pegasuscorp.orbe.bureau;

/**
 * Parse une réponse bureau Markdown : lignes {@code >} = TTS, le reste = contenu à afficher.
 * Nettoie les parasites LLM (echo du document, {@code ]]]} , fences orphelines).
 */
public final class BureauMarkdownParser {

    public static final class Parsed {
        /** Markdown à insérer ou fusionner (sans lignes {@code >}). */
        public final String markdown;
        /** Phrase(s) à lire à voix haute. */
        public final String speak;
        /** true si le modèle a renvoyé un document complet (remplace tout). */
        public final boolean replaceAll;

        public Parsed(String markdown, String speak, boolean replaceAll) {
            this.markdown = markdown == null ? "" : markdown.trim();
            this.speak = speak == null ? "" : speak.trim();
            this.replaceAll = replaceAll;
        }
    }

    private BureauMarkdownParser() {}

    public static Parsed parse(String raw) {
        return parse(raw, null);
    }

    /**
     * @param currentDocument document actuel (optionnel) — sert à retirer un echo complet
     */
    public static Parsed parse(String raw, String currentDocument) {
        if (raw == null || raw.trim().isEmpty()) {
            return new Parsed("", "", false);
        }
        String text = stripCodeFence(raw.trim());

        boolean replaceAll = false;
        if (text.contains("<!-- replace-all -->")) {
            replaceAll = true;
            text = text.replace("<!-- replace-all -->", "").trim();
        }
        int docMarker = indexOfDocumentMarker(text);
        if (docMarker == 0) {
            replaceAll = true;
            text = stripDocumentMarkerAt(text, 0);
        } else if (docMarker > 0) {
            String before = text.substring(0, docMarker).trim();
            String after = stripDocumentMarkerAt(text, docMarker);
            replaceAll = true;
            text = (before.isEmpty() ? "" : before + "\n") + after;
        }

        text = stripLeadingJunk(text);

        StringBuilder md = new StringBuilder();
        StringBuilder speak = new StringBuilder();
        boolean inFence = false;
        for (String line : text.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("```")) {
                inFence = !inFence;
                if (md.length() > 0) md.append('\n');
                md.append(line);
                continue;
            }
            if (!inFence && isJunkLine(trimmed)) continue;
            if (trimmed.startsWith(">") && !inFence) {
                String vocal = trimmed.startsWith("> ")
                        ? trimmed.substring(2).trim() : trimmed.substring(1).trim();
                if (trimmed.startsWith("> 💡") || trimmed.startsWith(">💡")) {
                    if (md.length() > 0) md.append('\n');
                    md.append(trimmed);
                    vocal = stripPegaseAnswerLabel(vocal);
                }
                if (!vocal.isEmpty()) {
                    if (speak.length() > 0) speak.append(' ');
                    speak.append(vocal);
                }
            } else {
                if (md.length() > 0) md.append('\n');
                md.append(line);
            }
        }
        String markdown = sanitizeMarkdownBody(md.toString().trim());
        if (!replaceAll) {
            markdown = stripDocumentEcho(markdown, currentDocument);
        } else {
            markdown = sanitizeMarkdownBody(markdown);
        }
        return new Parsed(markdown, speak.toString().trim(), replaceAll);
    }

    /** Corps markdown après retrait des parasites — préserve les fences ```…```. */
    static String sanitizeMarkdownBody(String markdown) {
        if (markdown == null || markdown.isEmpty()) return "";
        String[] lines = markdown.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        boolean inFence = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("```")) {
                inFence = !inFence;
                if (sb.length() > 0) sb.append('\n');
                sb.append(line);
                continue;
            }
            if (!inFence && isJunkLine(trimmed)) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(line);
        }
        String out = sb.toString().trim();
        // Parasites collés en fin de ligne / fin de doc
        out = out.replaceAll("\\]\\]\\]+\\s*$", "").trim();
        out = out.replaceAll("(?m)^\\s*\\]\\]\\]+\\s*$", "").trim();
        return out;
    }

    /**
     * Si le modèle a recopié le document actuel dans une réponse « patch »,
     * ne garde que les lignes nouvelles (hors echo).
     */
    static String stripDocumentEcho(String addition, String currentDocument) {
        if (addition == null || addition.isEmpty()) return "";
        String add = addition.trim();
        String doc = currentDocument == null ? "" : currentDocument.trim();
        if (doc.length() < 24) return add;

        // Echo exact ou quasi-exact → rien à insérer
        if (normalizeWs(add).equals(normalizeWs(doc))) return "";
        if (add.equals(doc) || doc.equals(add)) return "";

        // L'addition contient une grosse portion du doc → filtrer ligne à ligne
        String probe = doc.length() > 120 ? doc.substring(0, 120) : doc;
        boolean looksLikeEcho = add.contains(probe)
                || (add.length() > doc.length() * 0.7
                && shareFirstHeading(add, doc));
        if (!looksLikeEcho) return add;

        StringBuilder novel = new StringBuilder();
        for (String line : add.split("\n", -1)) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            if (isJunkLine(t)) continue;
            if (lineInDocument(t, doc)) continue;
            if (novel.length() > 0) novel.append('\n');
            novel.append(line);
        }
        return novel.toString().trim();
    }

    static boolean isJunkLine(String trimmed) {
        if (trimmed == null) return true;
        if (trimmed.isEmpty()) return false;
        if (trimmed.matches("^\\]+$")) return true;           // ]  ]]  ]]]
        if (trimmed.matches("^\\[+$")) return true;
        if (trimmed.equals("---") || trimmed.equals("***") || trimmed.equals("___")) {
            return false; // hr markdown OK
        }
        if (trimmed.equalsIgnoreCase("---DOCUMENT---")
                || trimmed.equalsIgnoreCase("DOCUMENT")
                || trimmed.equalsIgnoreCase("=== DOCUMENT ACTUEL ===")
                || trimmed.equalsIgnoreCase("=== DOCUMENT ACTUEL (contexte) ===")) {
            return true;
        }
        // Fence orpheline hors bloc : uniquement ```markdown / ```md (wrapper LLM).
        // ``` seul et ```mermaid sont conservés ailleurs via le suivi de fence.
        if (trimmed.equals("```markdown") || trimmed.equals("```md")) {
            return true;
        }
        return false;
    }

    private static boolean lineInDocument(String line, String document) {
        for (String dl : document.split("\n", -1)) {
            if (dl.trim().equals(line)) return true;
        }
        return false;
    }

    private static boolean shareFirstHeading(String a, String b) {
        String ha = firstHeading(a);
        String hb = firstHeading(b);
        return ha != null && ha.equals(hb);
    }

    private static String firstHeading(String md) {
        for (String line : md.split("\n", -1)) {
            String t = line.trim();
            if (t.startsWith("#")) return t;
        }
        return null;
    }

    private static String normalizeWs(String s) {
        return s.replace("\r\n", "\n").replaceAll("[ \t]+", " ").trim();
    }

    private static int indexOfDocumentMarker(String text) {
        if (text == null) return -1;
        int a = text.indexOf("---DOCUMENT---");
        int b = text.toLowerCase(java.util.Locale.ROOT).indexOf("---document---");
        if (a < 0) return b;
        if (b < 0) return a;
        return Math.min(a, b);
    }

    private static String stripDocumentMarkerAt(String text, int at) {
        int end = text.indexOf('\n', at);
        if (end < 0) return "";
        return text.substring(end + 1).trim();
    }

    private static String stripLeadingJunk(String text) {
        String[] lines = text.split("\n", -1);
        int i = 0;
        while (i < lines.length && isJunkLine(lines[i].trim()) && !lines[i].trim().isEmpty()) {
            i++;
        }
        if (i == 0) return text;
        StringBuilder sb = new StringBuilder();
        for (; i < lines.length; i++) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(lines[i]);
        }
        return sb.toString().trim();
    }

    private static String stripPegaseAnswerLabel(String vocal) {
        if (vocal == null) return "";
        String t = vocal.trim();
        if (t.startsWith("💡")) {
            t = t.substring("💡".length()).trim();
        }
        if (t.regionMatches(true, 0, "Pégase", 0, "Pégase".length())
                || t.regionMatches(true, 0, "Pegase", 0, "Pegase".length())) {
            int colon = t.indexOf(':');
            if (colon >= 0 && colon < 12) {
                return t.substring(colon + 1).trim();
            }
        }
        return vocal.trim();
    }

    private static String stripCodeFence(String raw) {
        if (!raw.startsWith("```")) return raw;
        int firstNl = raw.indexOf('\n');
        if (firstNl < 0) return raw;
        String lang = raw.substring(3, firstNl).trim().toLowerCase(java.util.Locale.ROOT);
        // Ne pas déballer un bloc Mermaid — l'aperçu a besoin de ```mermaid.
        if (lang.equals("mermaid") || lang.startsWith("mermaid ")) return raw;
        int end = raw.lastIndexOf("```");
        if (end <= firstNl) return raw.substring(firstNl + 1).trim();
        return raw.substring(firstNl + 1, end).trim();
    }
}
