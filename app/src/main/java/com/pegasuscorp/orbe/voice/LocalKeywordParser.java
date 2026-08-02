package com.pegasuscorp.orbe.voice;

import android.content.Context;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Implementation "niveau 1" du cerveau : parsing par mots-cles.
 *
 * Zero serveur, zero modele, zero conso au repos, reponse instantanee,
 * marche hors-ligne. Couvre les commandes courantes d'un launcher perso.
 *
 * Pour ajouter une commande : ajoute un "if" ci-dessous. C'est tout.
 */
public class LocalKeywordParser implements IntentParser {

    // "minuteur 5 minutes", "timer de 30 secondes", ...
    private static final Pattern DURATION =
            Pattern.compile("(\\d+)\\s*(min|minute|minutes|sec|seconde|secondes)");

    public LocalKeywordParser(Context ctx) {
        // Contexte garde pour une eventuelle localisation future ; inutilise ici.
    }

    @Override
    public Command parse(String transcript) {
        if (transcript == null) return Command.unknown();
        String t = transcript.toLowerCase().trim();

        // --- Minuteur ---
        if (t.contains("minuteur") || t.contains("timer") || t.contains("minute")) {
            int seconds = extractDuration(t);
            return new Command(Action.TIMER, null, seconds);
        }

        // --- Appel ---
        if (t.startsWith("appelle") || t.contains("telephone") || t.contains("appeler")) {
            return new Command(Action.CALL, null, 0);
        }

        // --- Tiroir d'apps ---
        if (t.contains("mes applications") || t.contains("toutes les apps")
                || t.contains("tiroir")) {
            return new Command(Action.OPEN_DRAWER, null, 0);
        }

        // --- Bloc-notes ---
        if ((t.contains("bloc") && t.contains("note"))
                || t.contains("liste des choses")) {
            if (t.contains("montre") || t.contains("affiche") || t.contains("ouvre")) {
                return new Command(Action.OPEN_NOTEPAD, null, 0);
            }
        }

        // --- Bureau Markdown ---
        if ((t.contains("bureau") || t.contains("mode markdown"))
                && (t.contains("ouvre") || t.contains("ouvrir") || t.contains("montre"))) {
            return new Command(Action.OPEN_BUREAU, null, 0);
        }

        // --- Interface Pégase ---
        if (t.contains("interface pegase") || t.contains("interface pégase")
                || t.contains("ton interface") || t.contains("la conversation")) {
            if (t.contains("ouvre") || t.contains("montre") || t.contains("affiche")) {
                return new Command(Action.OPEN_INTERFACE, null, 0);
            }
        }

        // --- Réglages API ---
        String fold = t.replace("é", "e").replace("è", "e")
                .replace("'", " ").replace("'", " ");
        if (fold.contains("cles api") || fold.contains("reglages api")
                || fold.contains("reglage api") || fold.contains("changer d api")
                || fold.contains("ouvre les api")) {
            return new Command(Action.OPEN_API_SETTINGS, null, 0);
        }

        // --- Ouvrir / relancer une app ou un raccourci web ---
        String foldT = t.replace('é', 'e').replace('è', 'e').replace('ê', 'e')
                .replace('-', ' ');
        for (String verb : new String[]{
                "relance ", "relancer ", "reouvre ", "reouvrir ",
                "ouvre encore ", "lance encore ", "ramene ",
                "ouvre moi ", "lance moi ", "ouvre ", "lance ",
                "demarre ", "ouvrir ", "lancer "
        }) {
            int idx = foldT.indexOf(verb);
            if (idx < 0) continue;
            String app = stripOpenArticles(foldT.substring(idx + verb.length()).trim());
            if (!app.isEmpty()
                    && !app.startsWith("ton interface")
                    && !app.startsWith("l'interface")
                    && !app.startsWith("l interface")
                    && !app.startsWith("la conversation")
                    && !app.contains("bloc")
                    && !app.contains("bureau")) {
                return new Command(Action.OPEN_APP, app, 0);
            }
        }

        return Command.unknown();
    }

    /** « le cursor » / « l'app cursor » → « cursor ». */
    static String stripOpenArticles(String app) {
        if (app == null) return "";
        String a = app.trim();
        a = a.replaceFirst("(?i)^(le|la|les|l['’]|un|une|mon|ma|mes)\\s+", "");
        a = a.replaceFirst("(?i)^(raccourci|lien|site|page|app|application)\\s+", "");
        a = a.replaceFirst("(?i)^(le|la|les|l['’]|un|une)\\s+", "");
        return a.trim();
    }

    private int extractDuration(String t) {
        Matcher m = DURATION.matcher(t);
        if (m.find()) {
            int value = Integer.parseInt(m.group(1));
            String unit = m.group(2);
            return unit.startsWith("min") ? value * 60 : value;
        }
        return 5 * 60; // defaut : 5 minutes
    }
}
