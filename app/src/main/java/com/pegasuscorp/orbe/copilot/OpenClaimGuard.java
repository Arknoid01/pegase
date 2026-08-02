package com.pegasuscorp.orbe.copilot;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Détecte les réponses LLM qui prétendent qu'une app / un lien est déjà ouvert
 * sans l'avoir relancé — halluciné après un premier {@code open_app}.
 */
public final class OpenClaimGuard {

    private OpenClaimGuard() {}

    public static boolean claimsAlreadyOpen(String reply) {
        if (reply == null || reply.isEmpty()) return false;
        String f = fold(reply);
        if (f.contains("tourne deja") || f.contains("tourne encore")
                || f.contains("tourait deja") || f.contains("tourait encore")) {
            return true;
        }
        if (f.contains("deja ouvert") || f.contains("deja ouverte")
                || f.contains("deja lance") || f.contains("deja lancee")
                || f.contains("deja en cours") || f.contains("est deja en train")) {
            return true;
        }
        if (f.contains("encore ouvert") || f.contains("encore ouverte")
                || f.contains("toujours ouvert") || f.contains("toujours en cours")) {
            return true;
        }
        return f.contains("pas besoin de") && (f.contains("ouvrir") || f.contains("relancer"));
    }

    /** Remplace une affirmation mensongère par le résultat outil (ou un OK court). */
    public static String replaceAlreadyOpenClaim(String reply, String toolFallback) {
        if (!claimsAlreadyOpen(reply)) return reply != null ? reply : "";
        if (toolFallback != null && !toolFallback.trim().isEmpty()) {
            return toolFallback.trim();
        }
        return "Je rouvre ça.";
    }

    public static boolean looksLikeOpenRequest(String user) {
        if (user == null || user.isEmpty()) return false;
        String f = fold(user);
        return f.contains("ouvre") || f.contains("ouvrir") || f.contains("lance ")
                || f.contains("lancer ") || f.contains("relance") || f.contains("reouvre")
                || f.contains("demarre") || f.contains("ramene");
    }

    static String fold(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return n.toLowerCase(Locale.ROOT);
    }
}
