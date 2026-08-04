package com.pegasuscorp.orbe.diag;

import com.pegasuscorp.orbe.session.Channel;

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
    public static final String TAGS_SUITE_ID = "tags_verify_v1";

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

    /**
     * Vérifie tools_chars (general) + tags conditionnels UI/F1/life/project
     * via {@code llm_prompt_budget.has_*} dans la trace.
     */
    public static List<DiagScript> tagsSuite() {
        return Collections.unmodifiableList(Arrays.asList(
                new DiagScript("general", "General ambigu",
                        "Dis-moi des trucs."),
                new DiagScript("ui_click", "UI click micro",
                        "Clique sur le bouton micro."),
                new DiagScript("ui_icone", "UI ouvre icône",
                        "Ouvre l'icône Astronomie."),
                new DiagScript("ui_copilot_ch", "UI via canal COPILOT",
                        "Montre le panneau du haut.", false, Channel.COPILOT),
                new DiagScript("f1", "F1 GP",
                        "Tu en as pensé quoi du GP ?"),
                new DiagScript("life", "Life pattern",
                        "Liste mes rythmes de vie."),
                new DiagScript("project", "Project object",
                        "Liste mes fiches projet.")
        ));
    }
}
