package com.pegasuscorp.orbe.session;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Détecte une demande d'arrêt du tour en cours (« stop », « laisse tomber »…).
 * Correspondance exacte sur des phrases courtes : « arrête la musique » n'annule pas le tour.
 */
public final class TurnCancelPolicy {

    private static final Set<String> CANCEL_PHRASES = new HashSet<>(Arrays.asList(
            "stop", "stop stop", "annule", "annule tout", "annuler", "arrete", "arrete tout",
            "arrete toi", "laisse tomber", "laisse", "oublie", "oublie ca", "non attends",
            "attends", "attends attends", "ca suffit", "c est bon", "pas la peine",
            "non laisse tomber", "non stop", "non annule", "tais toi", "pegase stop",
            "pegase annule"));

    private TurnCancelPolicy() {}

    public static boolean isCancel(String text) {
        if (text == null) return false;
        String folded = Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replaceAll("[^a-z0-9 ]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return CANCEL_PHRASES.contains(folded);
    }
}
