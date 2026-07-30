package com.pegasuscorp.orbe.orion.prompt;

import java.util.Locale;

/**
 * Normalisation texte Orion (casse, accents, apostrophes → espaces).
 * Partagé par {@link OrionMode} et {@link com.pegasuscorp.orbe.orion.TaskComplexityEstimator}.
 */
public final class TextFold {

    private TextFold() {}

    public static String fold(String text) {
        if (text == null) return "";
        return text.toLowerCase(Locale.ROOT)
                .replace('é', 'e').replace('è', 'e').replace('ê', 'e')
                .replace('à', 'a').replace('â', 'a')
                .replace('ô', 'o').replace('ù', 'u').replace('û', 'u')
                .replace('î', 'i').replace('ï', 'i')
                .replace('ç', 'c')
                .replace('’', '\'').replace('\'', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }
}
