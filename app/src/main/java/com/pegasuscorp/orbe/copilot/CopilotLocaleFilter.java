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

        int latin = 0;
        int accented = 0;
        int cjk = 0;
        int frenchHints = 0;
        String lower = t.toLowerCase(Locale.ROOT);
        String[] frWords = {" le ", " la ", " les ", " de ", " des ", " et ", " est ", " une ", " un "};
        for (String w : frWords) {
            if (lower.contains(w)) frenchHints++;
        }
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) cjk++;
            if ("àâäéèêëïîôùûüç".indexOf(Character.toLowerCase(c)) >= 0) accented++;
            if (Character.isLetter(c)) latin++;
        }
        if (frenchHints >= 2 && accented > 0) return false;
        if (cjk >= 2) return true;
        if (latin >= 8 && accented == 0 && !looksEnglish(lower)) {
            return true;
        }
        return latin >= 12 && accented == 0;
    }

    private static boolean looksEnglish(String lower) {
        return lower.contains(" the ") || lower.contains(" and ") || lower.contains(" you ")
                || lower.startsWith("the ") || lower.contains(" click ") || lower.contains(" submit ");
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
            if (!needsTranslation(node.text)) continue;
            out.add(node);
        }
        return out;
    }
}
