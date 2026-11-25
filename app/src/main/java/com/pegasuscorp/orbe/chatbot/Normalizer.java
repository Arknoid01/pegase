package com.pegasuscorp.orbe.chatbot;

import com.pegasuscorp.orbe.search.TexteFr;

import java.text.Normalizer.Form;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Nettoyage du message avant les règles et la recherche par sens (portage de
 * pegase-chatbot/chatbot/normalize.py). Yannick écrit vite, souvent à la voix : homophones
 * (« on n'a » pour « on a »), fautes de frappe, et « x) » / « ^^ » à la place des émojis.
 */
public final class Normalizer {

    public static final class Result {
        public final String raw;    // tel que reçu
        public final String text;   // corrigé, minuscules, sans signes de rire (recherche par sens)
        public final String key;    // en plus sans accents ni ponctuation (règles)
        public final boolean laughs;
        public final boolean sad;

        Result(String raw, String text, String key, boolean laughs, boolean sad) {
            this.raw = raw;
            this.text = text;
            this.key = key;
            this.laughs = laughs;
            this.sad = sad;
        }
    }

    private static final int U = TexteFr.U;
    private static final Pattern RIRE = Pattern.compile(
            "(?<!\\w)(x\\)|xd|\\^\\^|:\\)|:d|;\\)|😂|😅|🤣|😄|😁|😉|haha+|hihi+|mdr|lol)(?!\\w)",
            U | Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern TRISTE = Pattern.compile("(?<!\\w)(:\\(|:'\\(|😢|😭|😞)(?!\\w)", U);
    private static final Pattern MARQUES = Pattern.compile("\\p{M}+");
    private static final Pattern PONCT = Pattern.compile("[^\\w' ]+", U);
    private static final Pattern ESPACES = Pattern.compile("\\s+");

    /** Corrections fréquentes, sur le texte en minuscules. Ordre important. */
    private static final Pattern[] FAUTES;
    private static final String[] CORRIGES;

    static {
        String[][] c = {
                // « on n'a » pour « on a », sauf vraie négation (« on n'a pas / plus / jamais… »).
                {"\\bon n'(a|ai|est)\\b(?!\\s+(?:\\w+\\s+){0,2}(?:pas|plus|jamais|rien|aucun|aucune|personne)\\b)", "on $1"},
                {"\\bon n'ai\\b", "on est"},
                {"\\bque l'ont\\b", "que l'on"},
                {"\\bl'ont (fasse|fait|fera|sache|travail\\w*|parle|peut|va|n'a|a)\\b", "l'on $1"},
                {"\\bun peut\\b", "un peu"},
                {"\\btu est\\b", "tu es"},
                {"\\btues\\b", "tu es"},
                {"\\btu peut\\b", "tu peux"},
                {"\\btu pense\\b", "tu penses"},
                {"\\bon travail\\b", "on travaille"},
                {"\\bva y\\b", "vas-y"},
                {"\\bvas y\\b", "vas-y"},
                {"\\bquesque\\b", "qu'est-ce que"},
                {"\\bqu'elle (app|application|modele|model|heure|jour|film|musique)\\b", "quel $1"},
                {"\\bstp\\b", "s'il te plaît"},
                {"\\bcrever\\b", "crevé"},
                {"\\bca\\b", "ça"},
                {"\\bdesole\\b", "désolé"},
        };
        FAUTES = new Pattern[c.length];
        CORRIGES = new String[c.length];
        for (int i = 0; i < c.length; i++) {
            FAUTES[i] = Pattern.compile(c[i][0], U);
            CORRIGES[i] = c[i][1];
        }
    }

    private Normalizer() {}

    public static String stripAccents(String s) {
        return MARQUES.matcher(java.text.Normalizer.normalize(s, Form.NFD)).replaceAll("");
    }

    public static Result normalize(String raw) {
        String s = raw == null ? "" : raw.trim();
        boolean laughs = RIRE.matcher(s).find();
        boolean sad = TRISTE.matcher(s).find();
        s = RIRE.matcher(s).replaceAll(" ");
        s = TRISTE.matcher(s).replaceAll(" ");
        s = s.replace('’', '\'').toLowerCase(Locale.FRENCH);
        for (int i = 0; i < FAUTES.length; i++) s = FAUTES[i].matcher(s).replaceAll(CORRIGES[i]);
        s = ESPACES.matcher(s).replaceAll(" ").trim();
        String key = stripAccents(s);
        key = PONCT.matcher(key).replaceAll(" ");
        key = ESPACES.matcher(key).replaceAll(" ").trim();
        return new Result(raw, s, key, laughs, sad);
    }
}
