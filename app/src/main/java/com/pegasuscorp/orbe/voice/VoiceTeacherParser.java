package com.pegasuscorp.orbe.voice;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Mode professeur : « Apprends que quand je dis X, c'est Y » sans exécuter l'action. */
public final class VoiceTeacherParser {

    public static final class TeachRequest {
        public final String utterance;
        public final String actionPhrase;

        TeachRequest(String utterance, String actionPhrase) {
            this.utterance = utterance == null ? "" : utterance.trim();
            this.actionPhrase = actionPhrase == null ? "" : actionPhrase.trim();
        }

        public boolean isValid() {
            return utterance.length() >= 2 && actionPhrase.length() >= 3;
        }
    }

    private static final Pattern APPRENDS = Pattern.compile(
            "(?i)apprends\\s*(?:que\\s*)?(?::|—|-)?\\s*"
                    + "(?:quand je dis|si je dis|que)\\s+(.+?)\\s*,?\\s*"
                    + "(?:c'?est|ça veut dire|tu dois|je veux|tu)\\s+(.+)$");

    private static final Pattern NOTE = Pattern.compile(
            "(?i)(?:note|memorise|mémorise|retiens)\\s*(?:que\\s*)?"
                    + "(?:quand je dis|si je dis|que)\\s+(.+?)\\s*,?\\s*"
                    + "(?:c'?est|ça veut dire|tu dois|je veux|tu)\\s+(.+)$");

    private VoiceTeacherParser() {}

    public static boolean looksLikeTeach(String transcript) {
        return parse(transcript) != null;
    }

    public static TeachRequest parse(String transcript) {
        if (transcript == null) return null;
        String text = transcript.trim();
        if (text.isEmpty()) return null;

        Matcher m = APPRENDS.matcher(text);
        if (m.find()) return clean(m.group(1), m.group(2));

        m = NOTE.matcher(text);
        if (m.find()) return clean(m.group(1), m.group(2));

        return null;
    }

    private static TeachRequest clean(String utterance, String action) {
        String u = stripQuotes(utterance);
        String a = stripQuotes(action);
        u = u.replaceAll("(?i)\\s+sur spotify\\s*$", "").trim();
        a = a.replaceAll("[?.!]+$", "").trim();
        TeachRequest req = new TeachRequest(u, a);
        return req.isValid() ? req : null;
    }

    private static String stripQuotes(String text) {
        if (text == null) return "";
        String t = text.trim();
        if ((t.startsWith("\"") && t.endsWith("\""))
                || (t.startsWith("«") && t.endsWith("»"))) {
            t = t.substring(1, t.length() - 1).trim();
        }
        return t;
    }

    public static String buildConfirmQuestion(TeachRequest req, String actionLabel) {
        return "Quand tu dis « " + req.utterance + " », tu veux « "
                + actionLabel + " ». Je note sans lancer — c'est bien ça ?";
    }

    public static List<String> actionParts(String actionPhrase) {
        return LearnedToolPayload.splitCompositePhrase(actionPhrase);
    }
}
