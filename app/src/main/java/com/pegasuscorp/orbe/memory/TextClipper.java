package com.pegasuscorp.orbe.memory;

/**
 * Troncature texte pour injection prompt — préfère une frontière de phrase.
 */
public final class TextClipper {

    private TextClipper() {}

    /**
     * Coupe {@code text} en préférant {@code . ! ? ;} ou saut de ligne
     * dans {@code [softMax, hardMax]}. Sinon dernier espace avant hardMax.
     */
    public static String clipAtSentence(String text, int softMax, int hardMax) {
        if (text == null) return "";
        String t = text.trim();
        if (t.isEmpty()) return "";
        if (softMax < 1) softMax = 1;
        if (hardMax < softMax) hardMax = softMax;
        if (t.length() <= softMax) return t;
        if (t.length() <= hardMax) {
            // Déjà sous hardMax : garder tel quel (souvenir « court »).
            return t;
        }

        int windowEnd = Math.min(hardMax, t.length());
        int best = -1;
        for (int i = softMax; i < windowEnd; i++) {
            char c = t.charAt(i);
            if (c == '.' || c == '!' || c == '?' || c == ';' || c == '\n') {
                best = i;
            }
        }
        if (best >= softMax) {
            String cut = t.substring(0, best + 1).trim();
            return cut.isEmpty() ? t.substring(0, softMax).trim() + "…" : cut;
        }

        int space = t.lastIndexOf(' ', windowEnd);
        if (space >= Math.max(1, softMax / 2)) {
            return t.substring(0, space).trim() + "…";
        }
        return t.substring(0, windowEnd).trim() + "…";
    }
}
