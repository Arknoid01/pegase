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
    /** Réponse libre (zone faible) : même personnalité courte, un peu plus de place. */
    public static final double TEMP_LIBRE = 0.7;
    public static final int MAX_TOKENS_LIBRE = 160;
    /** Demande de travail (liste, idées, conseil) : 160 jetons coupaient la 3e idée (« 3. Optim »). */
    public static final int MAX_TOKENS_TACHE = 450;

    /** Consigne ajoutée avant une demande de travail (zone faible, {@code Decision.task}). */
    public static String taskPrompt() {
        return "[C'est une demande précise, pas une discussion : réponds à la demande elle-même, "
                + "concrètement. Respecte exactement le nombre, le format et les limites demandés. "
                + "Pas de salutation ni de compliment au début, pas de phrase sur toi-même. "
                + "Ici tu peux dépasser 3 phrases si la demande l'exige.]\n";
    }

    /** « ne me pose aucune question », « sans question »… (texte libre, minuscules ou non). */
    static final Pattern NO_QUESTION = Pattern.compile(
            "(ne|n')\\s*(me\\s+)?pose\\s+(aucune|pas\\s+de|plus\\s+de)\\s+questions?"
                    + "|sans\\s+(me\\s+)?poser\\s+de\\s+questions?|sans\\s+question|aucune\\s+question",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    public static boolean forbidsQuestions(String user) {
        return user != null && NO_QUESTION.matcher(user).find();
    }

    /** Retire les phrases interrogatives finales (« Qu'en penses-tu ? ») ; garde le reste. */
    public static String stripTrailingQuestions(String out) {
        String s = out.trim();
        while (s.endsWith("?")) {
            int cut = -1;
            for (int i = s.length() - 2; i >= 0; i--) {
                char c = s.charAt(i);
                if (c == '.' || c == '!' || c == '?' || c == '\n' || c == '…') { cut = i + 1; break; }
            }
            if (cut <= 0) return out.trim();   // tout le texte est une question : on n'y touche pas
            s = s.substring(0, cut).trim();
        }
        return s;
    }

    /** « Salut Yannick ! » / « Hey ! » en tête d'une réponse de travail. */
    private static final Pattern SALUTATION = Pattern.compile(
            "^(salut|coucou|hello|hey|bonjour)\\b[^.!?\\n]{0,30}[!.]\\s*", Pattern.CASE_INSENSITIVE);

    public static String stripGreeting(String out) {
        String s = SALUTATION.matcher(out).replaceFirst("");
        return s.isEmpty() ? out : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Personnalité courte de la reformulation (voice.py, validée sur PC le 2026-11-25). */
    public static String persona(String prenom) {
        return "Tu es Pégase, l'assistant de " + prenom + " sur son téléphone. Tu le tutoies.\n"
                + prenom + " est un homme : accorde au masculin (prêt, content, fier).\n"
                + "Ton ton : complice, chaleureux, une pointe d'humour taquin, jamais méchant.\n"
                + "Règles :\n"
                + "- Réponds en 1 à 3 phrases courtes, comme un SMS.\n"
                + "- N'invente jamais de souvenirs ni de vécu : tu es une IA, tu n'as pas de passé.\n"
                + "- Ne cite un fait sur " + prenom + " que s'il est fourni et qu'il a un rapport avec son message.\n"
                + "- Ne promets aucune action (noter, rappeler, chercher) : tu ne fais que discuter.\n"
                + "- Ne devine pas ce que pensent ou ressentent les autres (sa famille, ses proches).\n"
                + "- Tu peux finir par une question de relance, ou par une petite chute drôle et bienveillante.\n"
                + "- Émojis : au plus un, 😂 ou 😅, seulement si ça colle.";
    }

    /** Bloc ajouté au message (zone moyenne) : idées à reformuler, sans rien ajouter. */
    public static String ideasPrompt(String prenom, String message, ChatbotEngine.Decision d) {
        StringBuilder sb = new StringBuilder();
        sb.append("Message de ").append(prenom).append(" : « ").append(message).append(" »\n\n");
        sb.append("Idée de réponse à reformuler :\n");
        // Une seule idée : avec les suivantes, l'E2B mélangeait (banc téléphone 2026-11-25).
        if (!d.ideas.isEmpty()) sb.append("- ").append(d.ideas.get(0)).append('\n');
        // Pas de faits des fiches ici : l'idée contient déjà ce qu'il faut dire, et l'E2B
        // recrachait les faits hors sujet (prénoms des proches, détails des animaux… banc 2026-11-25).
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
