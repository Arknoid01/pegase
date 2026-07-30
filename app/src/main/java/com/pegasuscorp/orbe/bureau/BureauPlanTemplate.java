package com.pegasuscorp.orbe.bureau;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Squelette Markdown d'un plan Bureau (Objectifs / Décisions / Tâches / …).
 */
public final class BureauPlanTemplate {

    public static final String SECTION_OBJECTIFS = "Objectifs";
    public static final String SECTION_DECISIONS = "Décisions";
    public static final String SECTION_TACHES = "Tâches";
    public static final String SECTION_NOTES = "Notes / recherche";
    public static final String SECTION_HISTORIQUE = "Historique Pégase";

    /** Sections projet structuré (vue générée). */
    public static final String SECTION_VISION = BureauMarkdownBuilder.SECTION_VISION;
    public static final String SECTION_HYPOTHESES = BureauMarkdownBuilder.SECTION_HYPOTHESES;
    public static final String SECTION_A_VERIFIER = BureauMarkdownBuilder.SECTION_A_VERIFIER;
    public static final String SECTION_QUESTIONS = BureauMarkdownBuilder.SECTION_QUESTIONS;
    public static final String SECTION_REFERENCES = BureauMarkdownBuilder.SECTION_REFERENCES;
    public static final String SECTION_HISTORIQUE_PROJET = BureauMarkdownBuilder.SECTION_HISTORIQUE;

    public static final String SECTION_OBJECTIF = "Objectif";
    public static final String SECTION_CONTEXTE_TECH = "Contexte technique";
    public static final String SECTION_FICHIERS = "Fichiers concernés";
    public static final String SECTION_A_FAIRE = "À faire";
    public static final String SECTION_NE_PAS_TOUCHER = "Ne pas toucher";
    public static final String SECTION_CRITERES = "Critères OK";
    public static final String SECTION_NOTES_TECH = "Notes techniques";

    private BureauPlanTemplate() {}

    /** Session du jour — scratch léger avec structure de plan. */
    public static String dailyScratch() {
        String date = new SimpleDateFormat("d MMMM yyyy", Locale.FRENCH).format(new Date());
        return "# Bureau — " + date + "\n\n"
                + "## " + SECTION_VISION + "\n\n"
                + "## " + SECTION_OBJECTIFS + "\n\n"
                + "## " + SECTION_DECISIONS + "\n\n"
                + "## " + SECTION_TACHES + "\n"
                + "- [ ] \n\n"
                + "## " + SECTION_QUESTIONS + "\n\n"
                + "## " + SECTION_NOTES + "\n\n"
                + "## " + SECTION_HISTORIQUE + "\n\n";
    }

    /** Nouveau plan nommé. */
    public static String namedPlan(String title) {
        String t = (title == null || title.trim().isEmpty()) ? "Nouveau plan" : title.trim();
        return "# " + t + "\n\n"
                + "## " + SECTION_VISION + "\n\n"
                + "## " + SECTION_OBJECTIFS + "\n\n"
                + "## " + SECTION_DECISIONS + "\n\n"
                + "## " + SECTION_TACHES + "\n"
                + "- [ ] \n\n"
                + "## " + SECTION_QUESTIONS + "\n\n"
                + "## " + SECTION_NOTES + "\n\n"
                + "## " + SECTION_HISTORIQUE + "\n\n";
    }

    /** Squelette note technique (compatible PromptCompiler / Orion). */
    public static String technicalNote(String title) {
        String t = (title == null || title.trim().isEmpty())
                ? "Note technique" : title.trim();
        return "# " + t + "\n\n"
                + "## " + SECTION_OBJECTIF + "\n\n"
                + "## " + SECTION_CONTEXTE_TECH + "\n\n"
                + "## " + SECTION_FICHIERS + "\n"
                + "- \n\n"
                + "## " + SECTION_A_FAIRE + "\n"
                + "- [ ] \n\n"
                + "## " + SECTION_NE_PAS_TOUCHER + "\n"
                + "- \n\n"
                + "## " + SECTION_CRITERES + "\n"
                + "- \n\n"
                + "## " + SECTION_NOTES_TECH + "\n\n"
                + "## " + SECTION_HISTORIQUE + "\n\n";
    }

    public static boolean looksLikePlan(String markdown) {
        if (markdown == null) return false;
        String lower = markdown.toLowerCase(Locale.ROOT);
        return lower.contains("## objectifs")
                || lower.contains("## vision")
                || lower.contains("## tâches")
                || lower.contains("## taches")
                || lower.contains("## pitch")
                || lower.contains("cahier de conception");
    }

    public static boolean looksLikeTechnicalNote(String markdown) {
        if (markdown == null) return false;
        String lower = markdown.toLowerCase(Locale.ROOT);
        return lower.contains("## objectif")
                && (lower.contains("## à faire") || lower.contains("## a faire")
                || lower.contains("## ne pas toucher"));
    }
}
