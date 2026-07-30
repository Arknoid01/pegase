package com.pegasuscorp.orbe.voice;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Détecte les phrases floues (« mets un truc ») et propose une clarification naturelle.
 */
public final class VoicePhraseClarity {

    private static final Pattern VAGUE_PUT = Pattern.compile(
            "(?i)^(mets?|met|joue|lance|passe|balance|fais|mets? moi|joue moi|lance moi)\\s+"
                    + "(?:un truc|une truc|quelque chose|un peu|ca|ça|de la musique\\s*$|de la musique\\s+la)$");

    private static final Pattern VAGUE_WANT = Pattern.compile(
            "(?i)^(?:j'ai envie|j ai envie|je veux|je voudrais)\\s+(?:de\\s+)?"
                    + "(?:un truc|quelque chose|de la musique|musique)\\s*$");

    private static final Pattern VAGUE_SHORT = Pattern.compile(
            "(?i)^(mets?|joue|lance)\\s+(du|de la|des)?\\s*$");

    private VoicePhraseClarity() {}

    public static boolean isVague(String transcript) {
        if (transcript == null) return false;
        String t = transcript.trim();
        if (t.length() < 4) return true;
        String fold = fold(t);
        if (VAGUE_PUT.matcher(t).matches() || VAGUE_WANT.matcher(t).matches()) return true;
        if (VAGUE_SHORT.matcher(t).matches()) return true;
        if (fold.equals("mets un truc") || fold.equals("met un truc")
                || fold.equals("mets quelque chose") || fold.equals("lance quelque chose")
                || fold.equals("balance un truc") || fold.equals("fais un truc")
                || fold.equals("j ai envie de musique") || fold.equals("j ai envie de un truc")) {
            return true;
        }
        return fold.contains("un truc") && t.length() < 28
                || fold.contains("quelque chose") && t.length() < 32;
    }

    /** Meilleure hypothèse pour une phrase vague (sans exécuter). */
    public static String guessIntentHint(String transcript) {
        if (transcript == null) return "";
        String fold = fold(transcript);
        if (fold.contains("musique") || fold.contains("chanson") || fold.contains("son")
                || fold.contains("truc") || fold.contains("playlist")
                || fold.contains("spotify") || fold.contains("envie")) {
            return "spotify";
        }
        if (fold.contains("match") || fold.contains("foot") || fold.contains("score")) {
            return "sports";
        }
        if (fold.contains("meteo") || fold.contains("temps") || fold.contains("pluie")) {
            return "météo";
        }
        if (fold.contains("actu") || fold.contains("news") || fold.contains("info")) {
            return "actualités";
        }
        if (fold.contains("notif")) return "notifications";
        return "spotify";
    }

    public static String vagueClarificationQuestion(String userPhrase, String intentHint) {
        String quoted = quote(userPhrase);
        switch (intentHint == null ? "" : intentHint) {
            case "spotify":
                return "Quand tu dis " + quoted + ", tu parles de musique ?";
            case "sports":
                return "Quand tu dis " + quoted + ", tu parles d'un match ou d'un résultat sportif ?";
            case "météo":
                return "Quand tu dis " + quoted + ", tu veux la météo ?";
            case "actualités":
                return "Quand tu dis " + quoted + ", tu veux les actus ?";
            case "notifications":
                return "Quand tu dis " + quoted + ", tu parles de tes notifications ?";
            case "recherche":
                return "Quand tu dis " + quoted + ", tu veux que je cherche sur le web ?";
            default:
                return "Je ne suis pas sûre pour " + quoted + ". Tu peux préciser ?";
        }
    }

    private static String quote(String phrase) {
        if (phrase == null || phrase.trim().isEmpty()) return "ça";
        String t = phrase.trim();
        if (t.length() > 40) t = t.substring(0, 37) + "…";
        return "« " + t + " »";
    }

    private static String fold(String text) {
        String n = java.text.Normalizer.normalize(text.toLowerCase(Locale.ROOT),
                java.text.Normalizer.Form.NFD);
        n = n.replaceAll("\\p{M}", "");
        return n.replaceAll("\\s+", " ").trim();
    }
}
