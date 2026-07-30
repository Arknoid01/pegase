package com.pegasuscorp.orbe.chat;

import java.util.Locale;

/**
 * Message système après un outil réussi — avant la synthèse LLM.
 * Les outils d'<b>action</b> (spotify, notepad…) → courte confirmation.
 * Les outils d'<b>info</b> (search, wiki…) → synthèse du contenu, jamais « Ok ».
 */
public final class ToolSuccessHint {

    private ToolSuccessHint() {}

    public static boolean isInformational(String toolName) {
        String id = normalize(toolName);
        switch (id) {
            case "search":
            case "wikipedia":
            case "wikidata":
            case "news":
            case "weather":
            case "nasa":
            case "brief":
            case "calculator":
            case "named_context":
            case "diag":
                return true;
            default:
                return false;
        }
    }

    /**
     * @param toolName id outil
     * @param resultText texte / contexte pour la synthèse
     */
    public static String build(String toolName, String resultText) {
        String name = toolName == null || toolName.trim().isEmpty()
                ? "outil" : toolName.trim();
        String result = resultText == null ? "" : resultText.trim();
        int max = isInformational(name) ? 2400 : 800;
        if (result.length() > max) {
            result = result.substring(0, max - 1).trim() + "…";
        }

        if (isInformational(name)) {
            return "L'outil " + name + " a fourni des informations utiles.\n"
                    + "Résultat :\n" + result + "\n\n"
                    + "Réponds à la question de l'utilisateur en français oral "
                    + "(2 à 6 phrases), en t'appuyant sur ce résultat.\n"
                    + "INTERDIT : répondre seulement « Ok », « Compris », « Voilà », "
                    + "« C'est noté » ou une confirmation vide.\n"
                    + "Partage le fond concret (méthode, étapes, chiffres, faits). "
                    + "Pas d'URL, pas de liste à puces, pas de JSON, pas d'emoji.";
        }

        return "L'outil " + name + " vient de réussir. "
                + "Résultat : " + result + ". "
                + "Confirme ce succès simplement (une courte phrase).";
    }

    private static String normalize(String toolName) {
        if (toolName == null) return "";
        return toolName.trim().toLowerCase(Locale.ROOT);
    }
}
