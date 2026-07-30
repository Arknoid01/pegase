package com.pegasuscorp.orbe.orion.prompt;

/**
 * Mode d'exécution Orion : PATCH (défaut) ou FEATURE (mot entier « feature » dans la demande brute).
 * Détection unique — ne jamais appeler {@link #detect} sur du texte compilé.
 */
public enum OrionMode {
    PATCH,
    FEATURE;

    /**
     * Détecte le mode sur la demande utilisateur brute uniquement.
     * Mot entier « feature » (casse / accents via {@link TextFold}).
     */
    public static OrionMode detect(String rawUserDemand) {
        if (rawUserDemand == null || rawUserDemand.trim().isEmpty()) return PATCH;
        String fold = TextFold.fold(rawUserDemand);
        if (fold.isEmpty()) return PATCH;
        for (String token : fold.split(" ")) {
            if ("feature".equals(token)) return FEATURE;
        }
        return PATCH;
    }

    public String badgeLabel() {
        return this == FEATURE ? "MODE FEATURE" : "MODE PATCH";
    }
}
