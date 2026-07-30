package com.pegasuscorp.orbe.chat;

import java.util.Locale;

/**
 * Messages d'erreur chat lisibles par l'utilisateur (texte et voix).
 */
public final class ChatSpokenErrors {

    /** Affiché quand un provider renvoie HTTP 429 après les retry internes. */
    public static final String RATE_LIMIT_USER_MESSAGE =
            "Un provider cloud est saturé (souvent Groq). "
                    + "Réessaie dans une minute — ce n'est pas un problème de ton côté.";

    /**
     * Texte neutre stocké dans l'historique (ne doit PAS mentionner Groq/quota,
     * sinon le LLM répète ça à chaque tour suivant).
     */
    public static final String HISTORY_SAFE_TRANSIENT_ERROR =
            "Je n'ai pas pu répondre pour le moment. Réessaie.";

    /** Tous les providers de la chaîne ont échoué. */
    public static final String ALL_MODELS_FAILED_USER_MESSAGE =
            "Tous les providers (Groq, Cerebras, OpenRouter) ont échoué. "
                    + "Vérifie les clés dans Réglages, ou réessaie dans une minute.";

    private ChatSpokenErrors() {}

    public static boolean isRateLimit(String error) {
        if (error == null || error.trim().isEmpty()) return false;
        String lower = error.trim().toLowerCase(Locale.ROOT);
        return lower.contains("rate limit")
                || lower.contains("http 429")
                || lower.contains(" 429")
                || lower.contains("429 :")
                || lower.contains("too many requests")
                || lower.contains("quota api")
                || lower.contains("limite de requetes")
                || lower.contains("limite de requêtes")
                || lower.contains("requêtes groq")
                || lower.contains("requetes groq")
                || lower.contains("saturé")
                || lower.contains("sature");
    }

    /** Messages transport qui ne doivent jamais réalimenter le prompt LLM. */
    public static boolean isHistoryPoison(String text) {
        if (text == null || text.trim().isEmpty()) return false;
        if (text.equals(RATE_LIMIT_USER_MESSAGE)
                || text.equals(ALL_MODELS_FAILED_USER_MESSAGE)
                || text.equals(HISTORY_SAFE_TRANSIENT_ERROR)) {
            return true;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return isRateLimit(text)
                || lower.contains("quota api")
                || lower.contains("limite de requêtes groq")
                || lower.contains("limite de requetes groq")
                || lower.contains("service groq est indisponible")
                || lower.contains("service cloud est momentan")
                || lower.contains("momentanément satur")
                || lower.contains("momentanement satur")
                || lower.contains("tous les modèles ont échoué")
                || lower.contains("tous les modeles ont echoue")
                || lower.contains("tous les providers")
                || (lower.contains("clé ") && lower.contains("invalide"))
                || (lower.contains("groq") && (lower.contains("quota")
                || lower.contains("limite") || lower.contains("rate")));
    }

    /** Version safe pour l'historique conversationnel. */
    public static String toHistoryMessage(String error) {
        if (isRateLimit(error) || isAllModelsFailed(error) || isHistoryPoison(error)) {
            return HISTORY_SAFE_TRANSIENT_ERROR;
        }
        return toUserMessage(error);
    }

    public static boolean isAllModelsFailed(String error) {
        if (error == null || error.trim().isEmpty()) return false;
        String lower = error.trim().toLowerCase(Locale.ROOT);
        return lower.contains("tous les modèles")
                || lower.contains("tous les modeles")
                || lower.contains("modèles ont échoué")
                || lower.contains("modeles ont echoue")
                || lower.contains("modèles épuisés")
                || lower.contains("modeles epuises")
                || lower.contains("tous les providers")
                || lower.contains("all models failed")
                || lower.contains("all models exhausted");
    }

    /**
     * Groq/OpenAI : le modèle appelle un outil alors que tool_choice=none
     * (fin de boucle agentique), ou un outil absent de {@code request.tools}.
     */
    public static boolean isToolChoiceConflict(String error) {
        if (error == null || error.isEmpty()) return false;
        String lower = error.toLowerCase(Locale.ROOT);
        return lower.contains("tool choice is none")
                || lower.contains("which was not in request.tools")
                || lower.contains("not in request.tools")
                || (lower.contains("tool_use_failed")
                        && (lower.contains("tool choice") || lower.contains("tool_choice")));
    }

    /**
     * Extrait l'id d'outil manquant d'une erreur Groq/OpenAI du type
     * {@code Tool search was not in request.tools} / {@code 'search' which was not…}.
     * @return id normalisé, ou null si non parsable / conflit tool_choice=none
     */
    public static String parseMissingToolName(String error) {
        if (error == null || error.isEmpty()) return null;
        String lower = error.toLowerCase(Locale.ROOT);
        if (!lower.contains("not in request.tools")) return null;
        // "tool 'search' which was not" / "tool search was not" / "`search` was not"
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "(?:tool\\s+)?[`'\"]?([a-z][a-z0-9_]{0,40})[`'\"]?\\s+(?:which\\s+)?was\\s+not\\s+in\\s+request\\.tools")
                .matcher(lower);
        if (m.find()) {
            String name = m.group(1);
            if ("choice".equals(name) || "use".equals(name) || "calls".equals(name)) {
                return null;
            }
            return name;
        }
        return null;
    }

    /** Message unique pour l'UI texte, le fil de discussion et la voix. */
    public static String toUserMessage(String error) {
        return toUserMessage(null, error);
    }

    /**
     * @param providerLabel nom affiché (Groq, Cerebras…) si connu côté appelant
     */
    public static String toUserMessage(String providerLabel, String error) {
        if (error == null || error.trim().isEmpty()) {
            return "Désolé, une erreur est survenue.";
        }
        String msg = error.trim();
        String lower = msg.toLowerCase(Locale.ROOT);
        String provider = resolveProviderLabel(providerLabel, msg);

        // Déjà un message utilisateur — ne pas re-transformer
        if (msg.equals(RATE_LIMIT_USER_MESSAGE)
                || msg.equals(ALL_MODELS_FAILED_USER_MESSAGE)
                || msg.equals(HISTORY_SAFE_TRANSIENT_ERROR)) {
            return msg;
        }
        if (msg.contains(" est saturé (rate limit)")
                || (msg.startsWith("Clé ") && msg.contains("invalide. Vérifie"))) {
            return msg;
        }

        if (msg.contains("Clé Groq manquante") || msg.contains("Clé Gemini manquante")
                || msg.contains("Clé Cerebras manquante")
                || msg.contains("Clé OpenRouter manquante")
                || (msg.startsWith("Clé ") && msg.contains("manquante"))) {
            return msg;
        }
        if (isRateLimit(msg)) {
            if (provider != null) {
                return provider + " est saturé (rate limit). "
                        + "Je bascule si un autre provider est dispo — sinon réessaie dans une minute.";
            }
            return RATE_LIMIT_USER_MESSAGE;
        }
        if (isAllModelsFailed(msg)
                || msg.equals(ALL_MODELS_FAILED_USER_MESSAGE)) {
            return ALL_MODELS_FAILED_USER_MESSAGE;
        }
        if (lower.contains("401") || lower.contains("403")
                || lower.contains("invalid_api_key")
                || lower.contains("incorrect api key")
                || lower.contains("wrong api key")
                || lower.contains("user not found")
                || lower.contains("unauthorized")) {
            if (provider != null) {
                return "Clé " + provider + " invalide. Vérifie dans Réglages, section Clés API.";
            }
            return "Clé API invalide. Vérifie dans Réglages, section Clés API.";
        }
        if (lower.contains("http 503") || lower.contains("http 502") || lower.contains("http 500")) {
            if (provider != null) {
                return provider + " est indisponible pour l'instant. Réessaie dans une minute.";
            }
            return "Le service cloud est indisponible pour l'instant. Réessaie dans une minute.";
        }
        if (lower.contains("timeout") || lower.contains("timed out")
                || lower.contains("connectexception") || lower.contains("unknownhost")
                || lower.contains("failed to connect") || lower.contains("network")) {
            if (provider != null && lower.contains("timeout")) {
                return "Timeout " + provider + ". Réessaie ou vérifie ta connexion.";
            }
            return "Pas de connexion internet, je ne peux pas répondre.";
        }
        if (lower.contains("modèle") && lower.contains("charg")) {
            return msg;
        }
        if (msg.length() < 120 && !lower.startsWith("http")) {
            return msg;
        }
        if (provider != null) {
            return "Désolé, " + provider + " n'a pas pu répondre.";
        }
        return "Désolé, je n'ai pas pu obtenir de réponse.";
    }

    /** Alias de {@link #toUserMessage(String)} pour la voix. */
    public static String toSpoken(String error) {
        return toUserMessage(error);
    }

    /**
     * Déduit Groq / Cerebras / OpenRouter / Gemini depuis le label ou le texte d'erreur.
     */
    static String resolveProviderLabel(String explicit, String error) {
        if (explicit != null && !explicit.trim().isEmpty()) {
            String e = explicit.trim();
            if (e.equalsIgnoreCase("groq") || e.toLowerCase(Locale.ROOT).contains("groq")) {
                return "Groq";
            }
            if (e.equalsIgnoreCase("cerebras") || e.toLowerCase(Locale.ROOT).contains("cerebras")) {
                return "Cerebras";
            }
            if (e.equalsIgnoreCase("openrouter")
                    || e.toLowerCase(Locale.ROOT).contains("openrouter")) {
                return "OpenRouter";
            }
            if (e.equalsIgnoreCase("gemini") || e.toLowerCase(Locale.ROOT).contains("gemini")) {
                return "Gemini";
            }
            // Déjà un displayName propre
            if (e.length() <= 24 && !e.contains("HTTP") && !e.contains("{")) {
                return e;
            }
        }
        if (error == null) return null;
        String lower = error.toLowerCase(Locale.ROOT);
        if (lower.contains("openrouter")) return "OpenRouter";
        if (lower.contains("cerebras")) return "Cerebras";
        if (lower.contains("gemini")) return "Gemini";
        if (lower.contains("groq")) return "Groq";
        return null;
    }
}
