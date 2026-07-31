package com.pegasuscorp.orbe.copilot;

import java.util.Locale;

/** Filtre local par bloc de texte — décide si traduction cloud nécessaire. */
public final class CopilotLocaleFilter {

    private static final int MIN_LEN = 4;
    private static final int MAX_BLOCKS = 12;

    private CopilotLocaleFilter() {}

    public static boolean needsTranslation(String text) {
        if (text == null) return false;
        String t = text.trim();
        if (t.length() < MIN_LEN) return false;
        if (t.matches("^[\\d\\s\\p{Punct}]+$")) return false;
        if (isBrowserChromeLabel(t)) return false;
        if (looksFrench(t)) return false;

        int latin = 0;
        int accented = 0;
        int cjk = 0;
        String lower = t.toLowerCase(Locale.ROOT);
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) cjk++;
            if ("àâäéèêëïîôùûüç".indexOf(Character.toLowerCase(c)) >= 0) accented++;
            if (Character.isLetter(c)) latin++;
        }
        if (cjk >= 2) return true;
        // Latin sans accents : exige un vrai signal anglais (évite le FR chrome sans accents).
        if (latin >= 8 && accented == 0) {
            return looksEnglish(lower);
        }
        return false;
    }

    /** Libellés a11y navigateur / chrome UI — pas du contenu de page à traduire. */
    public static boolean isBrowserChromeLabel(String text) {
        if (text == null) return false;
        String lower = text.trim().toLowerCase(Locale.ROOT);
        if (lower.isEmpty()) return false;
        String[] chrome = {
                "ouvrir la page d'accueil", "nouvel onglet", "voir ", "onglets",
                "personnaliser et contrôler", "google chrome", "la connexion est sécurisée",
                "nouvelle fenêtre", "fermer l'onglet", "précédent", "suivant",
                "actualiser", "recharger", "partager", "ajouter aux favoris",
                "open home", "new tab", "secure connection", "customize and control",
                "omnibox", "search or type"
        };
        for (String c : chrome) {
            if (lower.contains(c)) return true;
        }
        // URL / domaine seuls
        if (lower.matches("^[a-z0-9.-]+\\.[a-z]{2,}(/\\S*)?$")) return true;
        if (lower.startsWith("http://") || lower.startsWith("https://")) return true;
        return false;
    }

    static boolean looksFrench(String text) {
        String lower = (" " + text.toLowerCase(Locale.ROOT) + " ")
                .replace('\'', ' ');
        String[] frWords = {
                " le ", " la ", " les ", " de ", " des ", " du ", " et ", " est ",
                " une ", " un ", " pour ", " dans ", " sur ", " avec ", " pas ",
                " qui ", " que ", " cette ", " ce ", " ouvrir ", " page ", " accueil ",
                " nouvel ", " onglet ", " connexion ", " sécurisée ", " rechercher ",
                " modifier ", " suivre ", " langue ", " télécharger ", " dernière ",
                " modification ", " politique ", " confidentialité ", " conditions "
        };
        int hints = 0;
        for (String w : frWords) {
            if (lower.contains(w)) hints++;
        }
        int accented = 0;
        for (int i = 0; i < text.length(); i++) {
            if ("àâäéèêëïîôùûüç".indexOf(Character.toLowerCase(text.charAt(i))) >= 0) {
                accented++;
            }
        }
        if (hints >= 2) return true;
        if (hints >= 1 && accented > 0) return true;
        if (accented >= 2) return true;
        return false;
    }

    private static boolean looksEnglish(String lower) {
        String padded = " " + lower + " ";
        return padded.contains(" the ") || padded.contains(" and ") || padded.contains(" you ")
                || padded.contains(" your ") || padded.contains(" with ") || padded.contains(" this ")
                || padded.contains(" that ") || padded.contains(" from ") || padded.contains(" for ")
                || lower.startsWith("the ") || padded.contains(" click ") || padded.contains(" submit ")
                || padded.contains(" welcome ") || padded.contains(" sign in ")
                || padded.contains(" log in ") || padded.contains(" continue ");
    }

    /** Blocs visibles à traduire (taille + filtre langue). */
    public static java.util.List<A11ySnapshot.Node> foreignBlocks(
            java.util.List<A11ySnapshot.Node> nodes) {
        java.util.List<A11ySnapshot.Node> out = new java.util.ArrayList<>();
        if (nodes == null) return out;
        for (A11ySnapshot.Node node : nodes) {
            if (out.size() >= MAX_BLOCKS) break;
            if (!node.hasBounds()) continue;
            if (node.width() < 24 || node.height() < 12) continue;
            // Chrome toolbar / barre d'adresse — hors contenu utile.
            if (node.top < 220 && node.height() < 120) continue;
            if (isBrowserChromeLabel(node.text)) continue;
            if (!needsTranslation(node.text)) continue;
            out.add(node);
        }
        return out;
    }
}
