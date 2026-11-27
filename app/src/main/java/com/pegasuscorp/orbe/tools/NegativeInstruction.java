package com.pegasuscorp.orbe.tools;

import com.pegasuscorp.orbe.voice.SpeechInputNormalizer;

import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Consigne négative de Yannick (« ne crée rien pour le moment », « montre-moi juste les
 * paramètres ») : elle l'emporte sur l'intention détectée par le routeur. Un outil qui écrit
 * (agenda, notes, minuteur, SMS, mémoire…) n'est alors pas exécuté (journal 2026-11-26).
 */
public final class NegativeInstruction {

    private NegativeInstruction() {}

    private static final Pattern FORBIDS = Pattern.compile(
            "(^|[^a-z])("
                    + "ne (cree|crees|creez|fais|faites|lance|programme|ajoute|enregistre|modifie"
                    + "|envoie|touche|change|supprime|efface|note)s? (rien|aucun|aucune|pas|encore)"
                    + "|ne rien (creer|faire|ajouter|modifier|enregistrer|envoyer|programmer|noter)"
                    + "|n'(ajoute|enregistre|envoie|efface)s? (rien|aucun|aucune|pas)"
                    + "|sans (rien )?(creer|enregistrer|ajouter|envoyer|programmer|le creer|la creer)"
                    + "|(presente|montre|donne|dis)[- ]moi (simplement|juste|seulement|uniquement)"
                    + " (les|le|ce que)"
                    + ")([^a-z]|$)");

    /** Outils qui ne font que lire, quelle que soit l'action. */
    private static final Set<String> READ_ACTIONS = new HashSet<>(Arrays.asList(
            "list", "show", "today", "aujourdhui", "aujourd'hui", "tomorrow", "demain", "week",
            "semaine", "open", "search", "read", "get", "status", "lookup", "find", "query"));

    private static final Set<String> WRITE_TOOLS = new HashSet<>(Arrays.asList(
            "agenda", "notepad", "timer", "alarm", "reminder", "memory", "sms", "call", "email",
            "messaging", "send_message", "calendar"));

    /** Vrai si la phrase interdit d'agir maintenant. */
    public static boolean forbidsAction(String userText) {
        if (userText == null || userText.trim().isEmpty()) return false;
        String f = SpeechInputNormalizer.fold(userText).replace('’', '\'');
        return FORBIDS.matcher(f).find();
    }

    /** Vrai si cet appel modifierait quelque chose (création, envoi, effacement…). */
    public static boolean isWriteCall(String toolId, JSONObject params) {
        if (toolId == null) return false;
        String id = toolId.trim().toLowerCase(Locale.ROOT);
        if (!WRITE_TOOLS.contains(id)) return false;
        String action = params != null ? params.optString("action", "").trim()
                .toLowerCase(Locale.ROOT) : "";
        return !READ_ACTIONS.contains(action);
    }

    /** Ce que l'outil aurait reçu, dit simplement, sans rien exécuter. */
    public static String dryRunReply(String toolId, JSONObject params) {
        StringBuilder sb = new StringBuilder("Je ne crée rien, comme tu me l'as demandé. ")
                .append("Voici ce que je transmettrais à l'outil ").append(toolId).append(" :");
        if (params != null) {
            java.util.Iterator<String> keys = params.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                if (k.startsWith("_") || "utterance".equals(k) || "query".equals(k)) continue;
                sb.append("\n• ").append(k).append(" : ").append(params.opt(k));
            }
        }
        if ("agenda".equalsIgnoreCase(toolId) || "alarm".equalsIgnoreCase(toolId)) {
            sb.append("\n• fuseau : Europe/Paris");
        }
        return sb.append("\nDis-moi « vas-y » pour que je le crée.").toString();
    }
}
