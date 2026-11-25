package com.pegasuscorp.orbe.chatbot;

import com.pegasuscorp.orbe.search.TexteFr;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * La « voix » (portage de pegase-chatbot/chatbot/voice.py) : consignes données à Gemma E2B
 * pour dire avec le ton de Pégase ce que le chatbot a trouvé, et garde-fou après coup.
 */
public final class ChatbotVoice {

    private ChatbotVoice() {}

    /** Température de la reformulation (zone moyenne) : basse, pour garder le sens. */
    public static final double TEMP_IDEES = 0.3;
    public static final int MAX_TOKENS_IDEES = 120;

    /** Personnalité courte de la reformulation (voice.py, validée sur PC le 2026-11-25). */
    public static String persona(String prenom) {
        return "Tu es Pégase, l'assistant de " + prenom + " sur son téléphone. Tu le tutoies.\n"
                + "Ton ton : complice, chaleureux, une pointe d'humour taquin, jamais méchant.\n"
                + "Règles :\n"
                + "- Réponds en 1 à 3 phrases courtes, comme un SMS.\n"
                + "- N'invente jamais de souvenirs ni de vécu : tu es une IA, tu n'as pas de passé.\n"
                + "- Ne cite un fait sur " + prenom + " que s'il est fourni et qu'il a un rapport avec son message.\n"
                + "- Tu peux finir par une question de relance, ou par une petite chute drôle et bienveillante.\n"
                + "- Émojis : au plus un, 😂 ou 😅, seulement si ça colle.";
    }

    /** Bloc ajouté au message (zone moyenne) : idées à reformuler, sans rien ajouter. */
    public static String ideasPrompt(String prenom, String message, ChatbotEngine.Decision d) {
        StringBuilder sb = new StringBuilder();
        sb.append("Message de ").append(prenom).append(" : « ").append(message).append(" »\n\n");
        sb.append("Idée de réponse à reformuler :\n");
        for (String i : d.ideas) sb.append("- ").append(i).append('\n');
        String faits = factsLine(d.facts);
        if (!faits.isEmpty()) sb.append(faits);
        sb.append("Reformule cette idée avec ton ton, en gardant son sens et ses informations.\n");
        sb.append("N'ajoute AUCUN fait nouveau. 1 à 3 phrases courtes.");
        return sb.toString();
    }

    /** Ligne « Ce que tu sais d'utile : … » (4 faits max), vide s'il n'y en a pas. */
    public static String factsLine(List<String> facts) {
        if (facts == null || facts.isEmpty()) return "";
        return "Ce que tu sais d'utile : " + String.join(" ; ", facts.subList(0, Math.min(4, facts.size()))) + ".\n";
    }

    private static final Pattern NOMBRE = Pattern.compile("\\d+(?:[.,]\\d+)?");
    private static final Pattern MOT = Pattern.compile("[\\wÀ-ÿ'’]+|[^\\w\\s]", TexteFr.U);

    /**
     * Nombres et noms propres qui doivent survivre à la reformulation. Un mot à majuscule ne
     * compte que s'il suit une lettre ou une virgule (pas un début de phrase, ni un émoji).
     */
    static Set<String> essentials(String text) {
        Set<String> out = new HashSet<>();
        Matcher n = NOMBRE.matcher(text);
        while (n.find()) out.add(n.group());
        Matcher m = MOT.matcher(text);
        String prev = "";
        while (m.find()) {
            String tok = m.group();
            if (tok.length() > 1 && Character.isUpperCase(tok.charAt(0))
                    && (prev.equals(",") || (!prev.isEmpty() && Character.isLetter(prev.charAt(0))))) {
                out.add(tok);
            }
            prev = tok;
        }
        return out;
    }

    /** Vrai si la reformulation a perdu un nombre ou un nom propre de l'idée. */
    public static boolean lostEssentials(String idea, String reformulated) {
        String low = reformulated.toLowerCase(Locale.FRENCH);
        for (String e : essentials(idea)) {
            if (!low.contains(e.toLowerCase(Locale.FRENCH))) return true;
        }
        return false;
    }

    /**
     * Vrai si la reformulation AJOUTE un nombre ou un nom propre absent de tout ce qui a été
     * fourni (idées, faits, message, derniers échanges) : un fait inventé.
     */
    public static boolean addedEssentials(String reformulated, String provided) {
        String low = provided == null ? "" : Normalizer.stripAccents(provided.toLowerCase(Locale.FRENCH));
        for (String e : essentials(reformulated)) {
            if (!low.contains(Normalizer.stripAccents(e.toLowerCase(Locale.FRENCH)))) return true;
        }
        return false;
    }

    // Émojis : l'E2B ignore la consigne (😉 🤔 🚀…) → on ne garde que ceux de Yannick.
    private static final Pattern EMOJI = Pattern.compile("[\\x{1F300}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE0F}]");
    private static final Pattern PONCT_FR = Pattern.compile("(?<=[\\wÀ-ÿ)»😂😅])([!?;]|:(?![\\d/]))", TexteFr.U);

    public static String cleanEmojis(String text) {
        Matcher m = EMOJI.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String e = m.group();
            m.appendReplacement(sb, e.equals("😂") || e.equals("😅") ? e : "");
        }
        m.appendTail(sb);
        // Ponctuation à la française : espace avant « ! ? ; : » (l'E2B l'oublie souvent).
        String s = PONCT_FR.matcher(sb.toString()).replaceAll(" $1");
        return s.replaceAll("[ \\t]{2,}", " ").trim();
    }
}
