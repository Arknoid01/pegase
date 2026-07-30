package com.pegasuscorp.orbe.bureau;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Projet Bureau structuré — source de vérité JSON.
 * Le Markdown est une vue générée ({@link BureauMarkdownBuilder}).
 */
public final class BureauProject {

    public enum Confidence {
        CONFIRMED,
        HYPOTHESIS,
        TO_VERIFY;

        public static Confidence fromString(String s) {
            if (s == null) return CONFIRMED;
            String u = s.trim().toUpperCase(Locale.ROOT).replace('-', '_');
            if (u.equals("CONFIRMED") || u.equals("CONFIRME") || u.equals("✓")
                    || u.equals("CONFIRMED")) {
                return CONFIRMED;
            }
            if (u.equals("HYPOTHESIS") || u.equals("HYPOTHESE") || u.equals("?")) {
                return HYPOTHESIS;
            }
            if (u.equals("TO_VERIFY") || u.equals("A_VERIFIER") || u.equals("!")
                    || u.equals("TOVERIFY")) {
                return TO_VERIFY;
            }
            try {
                return Confidence.valueOf(u);
            } catch (Exception e) {
                return CONFIRMED;
            }
        }
    }

    public String id = "";
    public String slug = "";
    public String title = "";
    public String vision = "";
    public final List<String> objectives = new ArrayList<>();
    public final List<Decision> decisions = new ArrayList<>();
    public final List<Task> tasks = new ArrayList<>();
    public final List<OpenQuestion> openQuestions = new ArrayList<>();
    public final List<Reference> references = new ArrayList<>();
    public final List<HistoryEntry> history = new ArrayList<>();
    public long createdAt;
    public long updatedAt;

    public static String newId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    public static String slugify(String title) {
        if (title == null || title.trim().isEmpty()) return "projet";
        String s = title.trim().toLowerCase(Locale.ROOT)
                .replace('é', 'e').replace('è', 'e').replace('ê', 'e')
                .replace('à', 'a').replace('â', 'a')
                .replace('ù', 'u').replace('û', 'u')
                .replace('ô', 'o').replace('î', 'i').replace('ï', 'i')
                .replace('ç', 'c')
                .replaceAll("[^a-z0-9\\s-]", "")
                .replaceAll("\\s+", "-")
                .replaceAll("-+", "-");
        if (s.startsWith("-")) s = s.substring(1);
        if (s.endsWith("-")) s = s.substring(0, s.length() - 1);
        if (s.isEmpty()) return "projet";
        if (s.length() > 48) s = s.substring(0, 48);
        return s;
    }

    public static final class Decision {
        public String id = "";
        public String text = "";
        public Confidence confidence = Confidence.CONFIRMED;
        public String reason = "";
        public long createdAt;
        public long updatedAt;
    }

    public static final class Task {
        public String id = "";
        public String text = "";
        public boolean done;
        public long createdAt;
        public long updatedAt;
    }

    public static final class OpenQuestion {
        public String id = "";
        public String text = "";
        public long createdAt;
        public long updatedAt;
    }

    public static final class Reference {
        public String id = "";
        public String title = "";
        public String path = "";
        public long createdAt;
        public long updatedAt;
    }

    public static final class HistoryEntry {
        public String id = "";
        public String text = "";
        public long createdAt;
    }
}
