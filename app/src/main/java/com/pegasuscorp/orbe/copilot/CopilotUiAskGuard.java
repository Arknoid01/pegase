package com.pegasuscorp.orbe.copilot;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Empêche le LLM de demander un {@code viewId} technique à l'utilisateur.
 * Le matching reste 100 % device-side (texte libre → arbre a11y).
 */
public final class CopilotUiAskGuard {

    private static final Pattern CLICK_TARGET = Pattern.compile(
            "(?i)^(?:p[eé]gase\\s+)?(?:s'il\\s+te\\s+pla[iî]t\\s+)?"
                    + "(?:peux[- ]tu\\s+|peut[- ]tu\\s+|tu\\s+peux\\s+)?"
                    + "(?:clique[rz]?|click|tape[rz]?|appuie[rz]?|ouvre[rz]?|active[rz]?)"
                    + "\\s+(?:sur\\s+|le\\s+|la\\s+|l['’]\\s*)?(.+?)\\s*[.!?]*$");

    private CopilotUiAskGuard() {}

    /** True si la réponse LLM réclame un id technique (viewId / ressource). */
    public static boolean asksForTechnicalViewId(String reply) {
        if (reply == null || reply.isEmpty()) return false;
        String f = fold(reply);
        if (f.contains("view_id") || f.contains("viewid") || f.contains("view id")) {
            return true;
        }
        if (f.contains("resource name") || f.contains("viewidresourcename")) {
            return true;
        }
        if (f.contains("identifiant de la vue") || f.contains("identifiant de vue")
                || f.contains("id de la vue") || f.contains("id de vue")) {
            return true;
        }
        if (f.contains("identifiant") && (f.contains("vue") || f.contains("view")
                || f.contains("contient le texte") || f.contains("element")
                || f.contains("bouton") || f.contains("cible"))) {
            return true;
        }
        // « il me faut l'id », « donne-moi l'id », « quel est l'id »…
        if ((f.contains("faut") || f.contains("besoin") || f.contains("donne")
                || f.contains(" indiqu") || f.contains("fournis") || f.contains("quel"))
                && (f.contains(" l'id") || f.contains(" l id") || f.contains("lid ")
                || f.contains("lid?") || f.endsWith("lid")
                || f.contains("identifiant"))) {
            return true;
        }
        return f.contains("android:id") || f.contains("id/text");
    }

    /**
     * Si {@code reply} réclame un viewId : remplace par le texte outil (ou un court OK).
     * Sinon renvoie {@code reply} inchangé.
     */
    public static String replaceTechnicalViewIdAsk(String reply, String toolFallback) {
        if (!asksForTechnicalViewId(reply)) return reply != null ? reply : "";
        if (toolFallback != null && !toolFallback.trim().isEmpty()) {
            return toolFallback.trim();
        }
        return "C'est fait.";
    }

    /**
     * Extrait la cible texte libre d'une consigne utilisateur
     * (« clique sur Astronomie et espace » → « Astronomie et espace »).
     */
    public static String inferUiTarget(String userText) {
        if (userText == null || userText.trim().isEmpty()) return "";
        String raw = userText.trim();
        Matcher m = CLICK_TARGET.matcher(raw);
        if (m.matches()) {
            String t = m.group(1).trim();
            t = t.replaceFirst("(?i)^(le|la|les|l['’]|un|une)\\s+", "").trim();
            return t;
        }
        return "";
    }

    static String fold(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return n.toLowerCase(Locale.ROOT);
    }
}
