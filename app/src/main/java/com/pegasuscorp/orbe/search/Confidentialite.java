package com.pegasuscorp.orbe.search;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Nettoyage d'une requête avant qu'elle quitte le téléphone (portage de confidentialite.py) :
 * e-mail, IBAN, téléphone, numéro + nom de rue, numéros longs, termes privés.
 */
public final class Confidentialite {

    private Confidentialite() {}

    private static final String[][] NOMS = {{"email"}, {"iban"}, {"téléphone"}, {"adresse"}, {"numéro long"}};
    private static final Pattern[] MOTIFS = {   // IBAN avant téléphone (un morceau d'IBAN ressemble à un numéro)
            Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.]+", TexteFr.U),
            Pattern.compile("\\b[A-Z]{2}\\d{2}(?:\\s?[0-9A-Z]{2,4}){4,8}\\b", TexteFr.U),
            Pattern.compile("(?:\\+33\\s?|0)[1-9](?:[\\s.-]?\\d{2}){4}", TexteFr.U),
            Pattern.compile("\\b\\d{1,4}\\s*(?:bis|ter)?\\s*,?\\s*(?:rue|avenue|av\\.|boulevard|bd|chemin|impasse|allée|place|route)"
                    + "\\s+(?:(?:des|du|de la|de l'|de|d')\\s*)?[\\w'-]+", TexteFr.UI),
            Pattern.compile("\\b\\d{9,}\\b", TexteFr.U),
    };

    /** [requête nettoyée ou null si plus rien d'utile] ; {@code retire} reçoit ce qui a été ôté. */
    public static String nettoyer(String requete, List<String> termesPrives, List<String> retire) {
        String q = requete == null ? "" : requete;
        for (int i = 0; i < MOTIFS.length; i++) {
            Matcher m = MOTIFS[i].matcher(q);
            while (m.find()) retire.add(NOMS[i][0]);
            q = MOTIFS[i].matcher(q).replaceAll(" ");
        }
        if (termesPrives != null) for (String t : termesPrives) {
            if (t == null || t.trim().isEmpty()) continue;
            Pattern p = Pattern.compile("(?<!\\w)" + Pattern.quote(t.trim()) + "(?!\\w)", TexteFr.UI);
            String ft = TexteFr.fold(t.trim());
            boolean vu = p.matcher(q).find() || TexteFr.fold(q).contains(ft);
            if (!vu) continue;
            retire.add("« " + t.trim() + " »");
            q = p.matcher(q).replaceAll(" ");
            // variante sans accents (même longueur : fold ne change pas la longueur ici en général)
            String fq = TexteFr.fold(q);
            int idx = fq.indexOf(ft);
            while (idx >= 0 && idx + t.trim().length() <= q.length()) {
                q = q.substring(0, idx) + " " + q.substring(idx + t.trim().length());
                fq = TexteFr.fold(q);
                idx = fq.indexOf(ft);
            }
        }
        q = q.replaceAll("\\s+", " ").trim().replaceAll("^[ ,;:]+|[ ,;:]+$", "");
        int utiles = 0;
        Matcher m = Pattern.compile("\\w+", TexteFr.U).matcher(q);
        while (m.find()) if (m.group().length() > 2) utiles++;
        return utiles >= 2 ? q : null;
    }
}
