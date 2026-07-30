package com.pegasuscorp.orbe.diag;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Scénarios courts pour isoler les problèmes dans trace.jsonl. */
public final class DiagScripts {

    /** Pause entre deux requêtes — limite les 429 Groq / surcharge Tavily. */
    public static final long COOLDOWN_MS = 12_000L;

    /** Délai max par requête avant passage à la suivante. */
    public static final long STEP_TIMEOUT_MS = 50_000L;

    public static final String SUITE_ID = "mini_diag_v2";

    /** Historique max attendu au 1er envoi (1 tour user courant). */
    public static final int FIRST_SEND_MAX_HISTORY = 1;

    private DiagScripts() {}

    public static List<DiagScript> miniSuite() {
        return Collections.unmodifiableList(Arrays.asList(
                new DiagScript("chat", "Discussion simple",
                        "Bonjour Pégase, réponds en une phrase courte."),
                new DiagScript("weather", "Météo",
                        "Quel temps demain ?"),
                new DiagScript("search", "Recherche web",
                        "Cherche l'heure à Tokyo en ce moment.", true)
        ));
    }
}
