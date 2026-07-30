package com.pegasuscorp.orbe.voice;

import android.content.Context;

import java.util.List;
import java.util.Locale;

/**
 * Confirmation vocale oui/non et questions de clarification.
 */
public final class VoiceConfirmation {

    private static final long CONFIRM_TIMEOUT_MS = 45_000;

    public static final class Pending {
        public final String toolJson;
        public final String userLine;
        public final String intentHint;
        public final String question;
        public final double confidence;
        public final long createdAt;
        public final boolean teachOnly;
        public final String teachUtterance;

        public Pending(String toolJson, String userLine, String intentHint,
                String question, double confidence) {
            this(toolJson, userLine, intentHint, question, confidence, false, null);
        }

        public Pending(String toolJson, String userLine, String intentHint,
                String question, double confidence, boolean teachOnly, String teachUtterance) {
            this.toolJson = toolJson;
            this.userLine = userLine;
            this.intentHint = intentHint;
            this.question = question;
            this.confidence = confidence;
            this.createdAt = System.currentTimeMillis();
            this.teachOnly = teachOnly;
            this.teachUtterance = teachUtterance;
        }

        public boolean isExpired() {
            return System.currentTimeMillis() - createdAt > CONFIRM_TIMEOUT_MS;
        }
    }

    public static final double HIGH_CONFIDENCE = 0.85;
    public static final double CONFIRM_THRESHOLD = 0.72;

    private VoiceConfirmation() {}

    public static boolean isYes(String transcript) {
        return isYesForPending(transcript);
    }

    /** Réponses courtes et sans ambiguïté — évite « oui mais plutôt… » qui confirme par erreur. */
    public static boolean isYesForPending(String transcript) {
        String fold = fold(transcript);
        if (fold.isEmpty() || fold.length() > 18) return false;
        return fold.equals("oui")
                || fold.equals("ouais")
                || fold.equals("ok")
                || fold.equals("okay")
                || fold.equals("exact")
                || fold.equals("exactement")
                || fold.equals("vas y")
                || fold.equals("allez y")
                || fold.equals("confirme")
                || fold.equals("c est ca")
                || fold.equals("c est ça");
    }

    public static boolean isCancel(String transcript) {
        String fold = fold(transcript);
        if (fold.isEmpty()) return false;
        return fold.equals("annule")
                || fold.startsWith("annule ")
                || fold.contains("laisse tomber")
                || fold.contains("oublie")
                || fold.equals("stop")
                || fold.contains("non merci");
    }

    /**
     * Nouvelle demande claire pendant une confirmation en attente — annule l'ancienne action.
     */
    public static boolean shouldOverridePending(Context ctx,
                                                String transcript, Pending pending) {
        if (transcript == null || pending == null) return false;
        String fold = fold(transcript);
        if (fold.length() < 10) return false;
        if (isYesForPending(transcript) || isNo(transcript) || isCancel(transcript)) {
            return false;
        }
        VoiceIntentRouter.RoutedIntent fresh = VoiceIntentRouter.analyze(ctx, transcript);
        if (fresh.directToolJson != null
                && !fresh.directToolJson.equals(pending.toolJson)) {
            return true;
        }
        if (fresh.directToolJson == null && fresh.intentHint == null && fold.length() >= 12) {
            return true;
        }
        return fold.contains("meteo")
                || fold.contains("bloc note")
                || fold.contains("bloc-note")
                || fold.contains("interface")
                || fold.contains("au revoir")
                || fold.contains("quitte le mode");
    }

    public static boolean isNo(String transcript) {
        String fold = fold(transcript);
        if (fold.isEmpty()) return false;
        return fold.equals("non")
                || fold.startsWith("non ")
                || fold.contains("annule")
                || fold.contains("laisse tomber")
                || fold.contains("pas ca")
                || fold.contains("pas ça")
                || fold.equals("stop")
                || fold.contains("oublie");
    }

    public static boolean needsConfirmation(double confidence, String intentHint) {
        return needsConfirmation(null, confidence, intentHint);
    }

    public static boolean needsConfirmation(Context context, double confidence, String intentHint) {
        return needsConfirmation(context, confidence, intentHint, null);
    }

    public static boolean needsConfirmation(Context context, double confidence, String intentHint,
            String userPhrase) {
        if (intentHint == null || intentHint.isEmpty()) return false;
        if (userPhrase != null && VoicePhraseClarity.isVague(userPhrase)) return true;

        boolean learnMode = context != null && LearnModeStore.isEnabled(context);
        if (learnMode) {
            if (confidence >= 0.96) return false;
            if (confidence >= 0.72) return true;
            return confidence > 0.48;
        }
        if (confidence >= 0.96) return false;
        if (confidence >= 0.84) return false;
        if (confidence >= 0.58) return true;
        return confidence > 0.45;
    }

    public static String buildQuestion(Context context, VoiceIntentRouter.RoutedIntent routed) {
        if (routed == null) return "Tu veux que je fasse ça ?";
        String userLine = routed.forLlm != null ? routed.forLlm : "";
        if (VoicePhraseClarity.isVague(userLine) && routed.intentHint != null) {
            return VoicePhraseClarity.vagueClarificationQuestion(userLine, routed.intentHint);
        }
        return buildNaturalQuestion(context, routed);
    }

    public static String buildNaturalQuestion(Context context, VoiceIntentRouter.RoutedIntent routed) {
        if (routed == null) return "Tu veux que je fasse ça ?";
        String userLine = routed.forLlm != null ? routed.forLlm.trim() : "";
        String quoted = userLine.isEmpty() ? "ça" : "« " + shorten(userLine) + " »";
        String hint = routed.intentHint != null ? routed.intentHint : "";
        String action = actionLabel(routed);

        if (context != null && LearnModeStore.isEnabled(context)) {
            if (!userLine.isEmpty()) {
                return "Quand tu dis " + quoted + ", tu veux " + action + " — c'est bien ça ?";
            }
        }

        if (!userLine.isEmpty() && routed.confidence < 0.84) {
            return "Pour " + quoted + ", tu veux " + action + " ?";
        }

        return buildLearnModeQuestion(routed, quoted, action, hint);
    }

    private static String actionLabel(VoiceIntentRouter.RoutedIntent routed) {
        if (routed.directToolJson != null && LearnedToolPayload.isComposite(routed.directToolJson)) {
            String label = LearnedToolPayload.label(routed.directToolJson);
            if (!label.isEmpty() && !"séquence".equals(label)) {
                return "lancer « " + label + " »";
            }
            return "lancer ta séquence";
        }
        String hint = routed.intentHint != null ? routed.intentHint : "";
        switch (hint) {
            case "spotify": return "de la musique sur Spotify";
            case "météo": return "la météo";
            case "sports": return "un résultat sportif";
            case "actualités": return "les actualités";
            case "nasa": return "la photo NASA du jour";
            case "recherche": return "que je cherche sur le web";
            case "notifications": return "gérer tes notifications";
            default:
                return hint.isEmpty() ? "que je fasse ça" : "« " + hint + " »";
        }
    }

    private static String shorten(String text) {
        if (text.length() <= 42) return text;
        return text.substring(0, 39) + "…";
    }

    public static String buildLearnModeQuestion(VoiceIntentRouter.RoutedIntent routed) {
        String userLine = routed != null && routed.forLlm != null ? routed.forLlm.trim() : "";
        String quoted = userLine.isEmpty() ? "ça" : "« " + shorten(userLine) + " »";
        String hint = routed != null && routed.intentHint != null ? routed.intentHint : "";
        return buildLearnModeQuestion(routed, quoted, actionLabel(routed), hint);
    }

    private static String buildLearnModeQuestion(VoiceIntentRouter.RoutedIntent routed,
            String quoted, String action, String hint) {
        if (routed == null) return "Tu veux que je fasse ça ?";
        switch (hint) {
            case "spotify":
                return "Tu veux que je lance de la musique sur Spotify ?";
            case "météo":
                return "Tu veux que je regarde la météo ?";
            case "sports":
                return "Tu parles du résultat sportif de cette équipe ?";
            case "actualités":
                return "Tu veux les actualités du jour ?";
            case "nasa":
                return "Tu veux la photo NASA du jour ?";
            case "recherche":
                return "Tu veux que je cherche ça sur le web ?";
            case "notifications":
                return "Tu veux que je gère tes notifications ?";
            default:
                if (hint.isEmpty()) {
                    return "Je ne suis pas sûre pour " + quoted + ". Tu précises ?";
                }
                return "Quand tu dis " + quoted + ", tu veux " + action + " ?";
        }
    }

    public static String buildDisambiguationQuestion(
            List<VoiceIntentRouter.DisambiguationOption> options) {
        if (options == null || options.isEmpty()) {
            return "Lequel voulais-tu ?";
        }
        if (options.size() == 2) {
            return "Je ne suis pas sûre : plutôt " + options.get(0).label
                    + ", ou plutôt " + options.get(1).label + " ?";
        }
        StringBuilder sb = new StringBuilder("Je ne suis pas sûre — ");
        for (int i = 0; i < options.size(); i++) {
            if (i > 0) sb.append(i == options.size() - 1 ? " et " : ", ");
            sb.append(options.get(i).label);
        }
        sb.append(". Lequel tu voulais ?");
        return sb.toString();
    }

    public static VoiceIntentRouter.DisambiguationOption resolveDisambiguationChoice(
            String transcript, List<VoiceIntentRouter.DisambiguationOption> options) {
        if (transcript == null || options == null || options.isEmpty()) return null;
        String fold = fold(transcript);
        if (fold.isEmpty()) return null;
        VoiceIntentRouter.DisambiguationOption best = null;
        int bestScore = 0;
        for (VoiceIntentRouter.DisambiguationOption opt : options) {
            String labelFold = fold(opt.label);
            String hintFold = fold(opt.intentHint);
            int score = 0;
            if (!labelFold.isEmpty() && (fold.equals(labelFold) || fold.contains(labelFold))) {
                score += 10;
            }
            if (!hintFold.isEmpty() && (fold.equals(hintFold) || fold.contains(hintFold))) {
                score += 8;
            }
            if (score > bestScore) {
                bestScore = score;
                best = opt;
            }
        }
        return bestScore > 0 ? best : null;
    }

    public static String buildQuestion(VoiceIntentRouter.RoutedIntent routed) {
        if (routed == null || routed.intentHint == null) {
            return "Tu veux que je fasse ça ?";
        }
        switch (routed.intentHint) {
            case "météo":
                return "Tu veux que je regarde la météo ?";
            case "sports":
                return "Tu veux le résultat sportif de cette équipe ?";
            case "actualités":
                return "Tu veux les actualités du jour ?";
            case "nasa":
                return "Tu veux la photo NASA du jour ?";
            case "recherche":
                return "Tu veux que je cherche ça sur le web ?";
            case "recherche web":
                return "Tu veux que j'ouvre une page web ?";
            default:
                return "Tu veux que je fasse ça ?";
        }
    }

    private static String fold(String text) {
        if (text == null) return "";
        String n = java.text.Normalizer.normalize(text.toLowerCase(Locale.ROOT),
                java.text.Normalizer.Form.NFD);
        n = n.replaceAll("\\p{M}", "");
        n = n.replace('\'', ' ').replace('’', ' ');
        return n.replaceAll("\\s+", " ").trim();
    }
}
