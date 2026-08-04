package com.pegasuscorp.orbe.f1companion;

import java.util.ArrayList;
import java.util.List;

/**
 * Dérive des {@code key_facts} factuels depuis les données structurées.
 * Pas d'opinion LLM ici — seulement des constats mesurables.
 */
public final class DebriefBuilder {

    private DebriefBuilder() {}

    public static void enrichKeyFacts(WeekendSnapshot snap) {
        if (snap == null) return;
        List<String> facts = new ArrayList<>();
        if (!snap.results.isEmpty()) {
            WeekendSnapshot.ResultRow w = snap.winner();
            if (w == null) w = snap.results.get(0);
            String winner = label(w);
            facts.add(winner + " remporte la course"
                    + (w.team.isEmpty() ? "" : " pour " + w.team) + ".");
            String podium = snap.podiumLine();
            if (!podium.isEmpty()) {
                facts.add("Podium : " + podium + ".");
            }
        }
        for (String delta : snap.positionDeltas()) {
            if (facts.size() >= 12) break;
            // Ne garder que les mouvements ≥ 3 places
            if (delta.contains("+") || delta.contains("(-")) {
                int gain = extractAbsDelta(delta);
                if (gain >= 3) facts.add(delta + ".");
            }
        }
        for (WeekendSnapshot.ResultRow r : snap.results) {
            if (facts.size() >= 14) break;
            if (r.dnf) facts.add(label(r) + " abandonne (DNF).");
            if (r.dsq) facts.add(label(r) + " est disqualifié.");
        }
        for (String inc : snap.incidents) {
            if (facts.size() >= 16) break;
            if (inc.startsWith("Safety Car") || inc.startsWith("Virtual")
                    || inc.startsWith("Drapeau")) {
                facts.add(inc + ".");
            }
        }
        snap.keyFacts.clear();
        snap.keyFacts.addAll(facts);
    }

    /** Prompt de synthèse pour le LLM (faits vs interprétation). */
    public static String buildSynthesisPrompt(WeekendSnapshot snap, String mode) {
        boolean deep = mode != null && mode.toLowerCase().contains("deep");
        StringBuilder sb = new StringBuilder();
        sb.append("=== FICHE GP (FAITS) ===\n");
        sb.append(snap.toMarkdown()).append("\n");
        if (deep) {
            sb.append("=== MODE DÉBRIEF APPROFONDI ===\n");
            sb.append("Structure ta réponse orale en sections courtes :\n");
            sb.append("1) Résumé course 2) Bataille victoire 3) Stratégies "
                    + "4) Écuries 5) Incidents 6) Tops/flops 7) Championnat si pertinent.\n");
            sb.append("Termine en proposant un angle : Ferrari, bataille podium, ou stratégie.\n");
        } else {
            sb.append("=== MODE DÉBRIEF RAPIDE (voix, ~2 min) ===\n");
            sb.append("2 à 4 phrases orales : podium + un fait marquant + une courte analyse "
                    + "marquée comme opinion (« Je pense que… » / « Le fait marquánt pour moi… »).\n");
        }
        sb.append("N'invente aucun chiffre hors fiche. Pas d'URL, pas de JSON, pas d'emoji.\n");
        return sb.toString();
    }

    /**
     * Prompt débrief + mémoire fan (avis, pronostics, écuries).
     */
    public static String buildSynthesisPrompt(WeekendSnapshot snap, String mode,
            F1FanMemory fanMemory, java.util.List<FavoriteTeamsStore.TeamDef> favorites) {
        String base = buildSynthesisPrompt(snap, mode);
        if (fanMemory == null || fanMemory.isEmpty()) {
            if (favorites == null || favorites.isEmpty()) return base;
        }
        StringBuilder sb = new StringBuilder(base);
        sb.append("\n=== MÉMOIRE FAN ===\n");
        if (fanMemory != null) {
            sb.append(fanMemory.toMarkdown(favorites)).append("\n");
        } else if (favorites != null && !favorites.isEmpty()) {
            sb.append("Écuries suivies : ");
            for (int i = 0; i < favorites.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(favorites.get(i).label);
            }
            sb.append(".\n");
        }
        sb.append("Si un avis ou un pronostic collé au débat, rappelle-le (chambrage léger OK).\n");
        return sb.toString();
    }

    public static String quickSpeech(WeekendSnapshot snap) {
        if (snap == null || !snap.hasRaceResults()) {
            return "Je n'ai pas encore de fiche de Grand Prix.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Fiche prête pour ").append(snap.event).append(". ");
        String podium = snap.podiumLine();
        if (!podium.isEmpty()) {
            sb.append("Podium : ").append(podium).append(". ");
        }
        if (!snap.keyFacts.isEmpty()) {
            sb.append(snap.keyFacts.get(0));
            if (!snap.keyFacts.get(0).endsWith(".")) sb.append('.');
            sb.append(' ');
        }
        sb.append("Demande-moi ce que j'en pense, ou un angle (stratégie, Ferrari, Safety Car…).");
        return sb.toString().trim();
    }

    private static String label(WeekendSnapshot.ResultRow r) {
        if (r.driver != null && !r.driver.isEmpty()) return r.driver;
        return "#" + r.driverNumber;
    }

    private static int extractAbsDelta(String line) {
        try {
            int i = line.lastIndexOf('(');
            int j = line.lastIndexOf(')');
            if (i < 0 || j <= i) return 0;
            String inner = line.substring(i + 1, j).replace("+", "").replace(" places", "").trim();
            return Math.abs(Integer.parseInt(inner.split(" ")[0]));
        } catch (Exception e) {
            return 0;
        }
    }
}
