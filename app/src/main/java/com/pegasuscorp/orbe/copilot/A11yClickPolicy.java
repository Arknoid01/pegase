package com.pegasuscorp.orbe.copilot;

import android.text.TextUtils;

import java.util.Arrays;
import java.util.Locale;

/**
 * Politique de confirmation pour les clics copilote v4.
 */
public final class A11yClickPolicy {

    public enum Level {
        /** Clic direct sans confirmation. */
        NEVER,
        /** Confirmation si denylist sur la cible d'origine. */
        CONDITIONAL,
        /** Toujours confirmer (lien détecté). */
        ALWAYS
    }

    private static final String[] DENYLIST = {
            "envoyer", "send", "supprimer", "delete", "effacer", "payer", "pay",
            "acheter", "buy", "confirmer", "valider", "desactiver", "disable"
    };

    private A11yClickPolicy() {}

    public static Level evaluate(A11yUiMatcher.Target target) {
        if (target == null) return Level.NEVER;
        if (isLink(target)) return Level.ALWAYS;
        if (matchesDenylist(target)) return Level.CONDITIONAL;
        return Level.NEVER;
    }

    public static boolean isLink(A11yUiMatcher.Target target) {
        if (target == null) return false;
        String cls = fold(target.className);
        String text = fold(target.text);
        String viewId = fold(target.viewId);
        if (cls.contains("link")) return true;
        if (viewId.contains("link") || viewId.contains("url")) return true;
        if (text.startsWith("http://") || text.startsWith("https://") || text.startsWith("www.")) {
            return true;
        }
        return false;
    }

    public static boolean matchesDenylist(A11yUiMatcher.Target target) {
        if (target == null) return false;
        String hay = fold(target.text) + " " + fold(target.viewId);
        if (TextUtils.isEmpty(hay.trim())) return false;
        for (String word : DENYLIST) {
            if (containsToken(hay, word)) return true;
        }
        return false;
    }

    public static String buildConfirmQuestion(A11yUiMatcher.Target target, Level level) {
        String label = target != null && !TextUtils.isEmpty(target.text)
                ? target.text : (target != null ? target.viewId : "cet élément");
        if (level == Level.ALWAYS) {
            return "Ce bouton ressemble à un lien (« " + label + " »). Je clique ?";
        }
        return "L'action « " + label + " » peut être sensible. Je confirme le clic ?";
    }

    static boolean containsToken(String hay, String token) {
        if (TextUtils.isEmpty(hay) || TextUtils.isEmpty(token)) return false;
        int idx = hay.indexOf(token);
        while (idx >= 0) {
            boolean startOk = idx == 0 || !Character.isLetterOrDigit(hay.charAt(idx - 1));
            int end = idx + token.length();
            boolean endOk = end >= hay.length() || !Character.isLetterOrDigit(hay.charAt(end));
            if (startOk && endOk) return true;
            idx = hay.indexOf(token, idx + 1);
        }
        return false;
    }

    private static String fold(String text) {
        return A11yUiMatcher.fold(text);
    }
}
