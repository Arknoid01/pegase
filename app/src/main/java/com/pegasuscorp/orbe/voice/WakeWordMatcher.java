package com.pegasuscorp.orbe.voice;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Détecte le mot d'éveil « Pégase » dans une transcription STT.
 */
public final class WakeWordMatcher {

    private static final Pattern WAKE_PREFIX = Pattern.compile(
            "(?i)^(?:hey|bonjour|salut|euh|ben|ok)?\\s*p[ée]gase[!?,\\.\\s]*");

    private WakeWordMatcher() {}

    public static boolean containsWakeWord(String transcript) {
        if (transcript == null || transcript.trim().isEmpty()) return false;
        String fold = fold(transcript);
        if (matchesWake(fold)) return true;
        String normalized = SpeechInputNormalizer.normalize(transcript);
        if (normalized != null && !normalized.equals(transcript)) {
            return matchesWake(fold(normalized));
        }
        return false;
    }

    /**
     * Wake-only, sans {@link SpeechInputNormalizer#normalize} :
     * évite de tirer PegasePrompt / VoiceCorrectionStore dans le process {@code :voice}.
     */
    public static boolean containsWakeWordLite(String transcript) {
        if (transcript == null || transcript.trim().isEmpty()) return false;
        return matchesWake(fold(transcript));
    }

    /** Strip wake en mode lite (pas de normalize LLM). */
    public static String stripWakePrefixLite(String transcript) {
        if (transcript == null) return "";
        String t = transcript.trim();
        if (t.isEmpty()) return "";
        if (!containsWakeWordLite(t)) return t;
        Matcher lead = WAKE_PREFIX.matcher(t);
        if (lead.find() && lead.start() == 0) {
            return cleanTail(lead.replaceFirst(""));
        }
        Matcher anywhere = Pattern.compile("(?i)p[ée]gase").matcher(t);
        if (anywhere.find()) {
            return cleanTail(t.substring(anywhere.end()));
        }
        String folded = fold(t);
        for (String wake : new String[]{"pegase", "pegasse", "pegaze"}) {
            int idx = folded.indexOf(wake);
            if (idx >= 0) {
                return cleanTail(t.substring(Math.min(t.length(), idx + wake.length())));
            }
        }
        return t;
    }

    private static boolean matchesWake(String fold) {
        if (fold.equals("pegase")) return true;
        if (fold.startsWith("pegase ")) return true;
        if (fold.startsWith("hey pegase")) return true;
        if (fold.startsWith("bonjour pegase")) return true;
        if (fold.startsWith("salut pegase")) return true;
        if (fold.contains(" pegase")) return true;
        // Variantes STT fréquentes
        if (fold.equals("pegasse") || fold.startsWith("pegasse ")) return true;
        if (fold.equals("pegaze") || fold.startsWith("pegaze ")) return true;
        if (fold.startsWith("pegas ") && fold.length() <= 12) return true;
        return fold.contains(" pegasse") || fold.contains(" pegaze");
    }

    /** Texte après le mot d'éveil (commande immédiate), ou la phrase entière si pas de wake. */
    public static String stripWakePrefix(String transcript) {
        if (transcript == null) return "";
        String t = transcript.trim();
        if (t.isEmpty()) return "";
        if (!containsWakeWord(t)) {
            return t;
        }
        Matcher lead = WAKE_PREFIX.matcher(t);
        if (lead.find() && lead.start() == 0) {
            return cleanTail(lead.replaceFirst(""));
        }
        Matcher anywhere = Pattern.compile("(?i)p[ée]gase").matcher(t);
        if (anywhere.find()) {
            return cleanTail(t.substring(anywhere.end()));
        }
        String fold = fold(SpeechInputNormalizer.normalize(t));
        for (String wake : new String[]{"pegase", "pegasse", "pegaze"}) {
            int idx = fold.indexOf(wake);
            if (idx >= 0) {
                return cleanTail(t.substring(Math.min(t.length(), idx + wake.length())));
            }
        }
        return t;
    }

    private static String cleanTail(String text) {
        if (text == null) return "";
        return text.trim().replaceAll("^[,\\s]+", "").replaceAll("[?.!]+$", "").trim();
    }

    private static String fold(String text) {
        if (text == null) return "";
        return SpeechInputNormalizer.fold(text).replace('\'', ' ')
                .replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }
}
