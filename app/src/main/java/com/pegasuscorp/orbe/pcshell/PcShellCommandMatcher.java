package com.pegasuscorp.orbe.pcshell;

import com.pegasuscorp.orbe.voice.SpeechInputNormalizer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Correspondance floue libellé dicté ↔ {@link SavedCommand#label} enregistré.
 */
public final class PcShellCommandMatcher {

    /** Score minimum pour accepter un match. */
    public static final double MATCH_THRESHOLD = 0.62;
    /** Au-delà : pas de confirmation vocale standard (sauf sudo). */
    public static final double HIGH_CONFIDENCE = 0.88;

    public static final class Match {
        public final SavedCommand command;
        public final double score;

        Match(SavedCommand command, double score) {
            this.command = command;
            this.score = score;
        }
    }

    private PcShellCommandMatcher() {}

    public static Match bestMatch(List<SavedCommand> commands, String spokenLabel) {
        if (commands == null || commands.isEmpty()) return null;
        String query = normalize(spokenLabel);
        if (query.isEmpty()) return null;

        Match best = null;
        for (SavedCommand cmd : commands) {
            if (cmd == null || cmd.label == null || cmd.label.trim().isEmpty()) continue;
            double score = scoreLabels(normalize(cmd.label), query);
            if (score >= MATCH_THRESHOLD && (best == null || score > best.score)) {
                best = new Match(cmd, score);
            }
        }
        return best;
    }

    public static List<String> listLabels(List<SavedCommand> commands) {
        List<String> out = new ArrayList<>();
        if (commands == null) return out;
        for (SavedCommand c : commands) {
            if (c != null && c.label != null && !c.label.trim().isEmpty()) {
                out.add(c.label.trim());
            }
        }
        return out;
    }

    static String normalize(String text) {
        if (text == null) return "";
        return SpeechInputNormalizer.fold(text.trim())
                .replaceAll("\\s+", " ")
                .trim();
    }

    static double scoreLabels(String labelFold, String queryFold) {
        if (labelFold.isEmpty() || queryFold.isEmpty()) return 0;
        if (labelFold.equals(queryFold)) return 1.0;

        if (labelFold.contains(queryFold)) {
            return 0.85 + 0.1 * ((double) queryFold.length() / labelFold.length());
        }
        if (queryFold.contains(labelFold)) {
            return 0.82 + 0.08 * ((double) labelFold.length() / queryFold.length());
        }

        double tokenScore = tokenOverlap(labelFold, queryFold);
        double levScore = levenshteinRatio(labelFold, queryFold);
        return Math.max(tokenScore, levScore * 0.95);
    }

    static double tokenOverlap(String a, String b) {
        String[] ta = a.split(" ");
        String[] tb = b.split(" ");
        if (ta.length == 0 || tb.length == 0) return 0;
        Set<String> setB = new HashSet<>();
        for (String t : tb) {
            if (!t.isEmpty()) setB.add(t);
        }
        int hit = 0;
        for (String t : ta) {
            if (setB.contains(t)) hit++;
        }
        int denom = Math.max(ta.length, tb.length);
        if (denom == 0) return 0;
        double ratio = (double) hit / denom;
        if (ratio >= 0.5) return 0.7 + ratio * 0.25;
        return ratio * 0.65;
    }

    static double levenshteinRatio(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        int maxLen = Math.max(a.length(), b.length());
        if (maxLen > 48) return 0;
        int dist = levenshtein(a, b);
        return 1.0 - ((double) dist / maxLen);
    }

    static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
        }
        return prev[b.length()];
    }

    public static boolean requiresSudo(SavedCommand cmd) {
        if (cmd == null || cmd.command == null) return false;
        String c = cmd.command.toLowerCase(Locale.ROOT);
        return c.contains("sudo ");
    }
}
