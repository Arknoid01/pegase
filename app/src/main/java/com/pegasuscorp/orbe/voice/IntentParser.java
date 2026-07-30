package com.pegasuscorp.orbe.voice;

/**
 * LE "cerveau" de l'assistant, sous forme d'interface.
 *
 * C'est la piece maitresse de l'architecture : MainActivity ne connait QUE
 * cette interface, jamais l'implementation. Aujourd'hui on branche
 * {@link LocalKeywordParser} (mots-cles, 0 serveur, 0 batterie au repos).
 *
 * Le jour ou tu veux un vrai LLM embarque, tu crees un GemmaParser qui
 * implemente cette meme interface et tu changes UNE ligne dans MainActivity :
 *
 *     intentParser = new LocalKeywordParser(this);   // avant
 *     intentParser = new GemmaParser(this);          // apres
 *
 * Aucun autre code a toucher. Le reste du launcher ne voit pas la difference.
 */
public interface IntentParser {

    enum Action {
        OPEN_APP,          // "ouvre whatsapp"   -> argument = "whatsapp"
        CALL,              // "appelle", "telephone"
        TIMER,             // "minuteur 5 minutes" -> number = 300 (secondes)
        OPEN_DRAWER,       // "montre mes applications"
        OPEN_NOTEPAD,      // "montre le bloc-notes"
        OPEN_INTERFACE,    // "ouvre ton interface"
        OPEN_BUREAU,       // "ouvre le bureau"
        OPEN_API_SETTINGS, // "ouvre les réglages API"
        UNKNOWN            // rien de reconnu
    }

    /** Resultat structure d'une phrase dictee. */
    class Command {
        public final Action action;
        public final String argument;   // ex: nom d'app, peut etre null
        public final int number;        // ex: duree en secondes, 0 si absent

        public Command(Action action, String argument, int number) {
            this.action = action;
            this.argument = argument;
            this.number = number;
        }

        public static Command unknown() {
            return new Command(Action.UNKNOWN, null, 0);
        }
    }

    /** Transforme une phrase en Command executable. */
    Command parse(String transcript);
}
