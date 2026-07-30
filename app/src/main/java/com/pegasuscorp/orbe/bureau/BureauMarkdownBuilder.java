package com.pegasuscorp.orbe.bureau;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Rendu déterministe {@link BureauProject} → Markdown (vue lecture seule).
 */
public final class BureauMarkdownBuilder {

    public static final String SECTION_VISION = "Vision";
    public static final String SECTION_OBJECTIFS = "Objectifs";
    public static final String SECTION_DECISIONS = "Décisions";
    public static final String SECTION_HYPOTHESES = "Hypothèses";
    public static final String SECTION_A_VERIFIER = "À vérifier";
    public static final String SECTION_TACHES = "Tâches";
    public static final String SECTION_QUESTIONS = "Questions ouvertes";
    public static final String SECTION_REFERENCES = "Références";
    public static final String SECTION_HISTORIQUE = "Historique";

    private BureauMarkdownBuilder() {}

    public static String render(BureauProject project) {
        if (project == null) return "";
        StringBuilder sb = new StringBuilder();
        String title = project.title == null || project.title.trim().isEmpty()
                ? "Projet" : project.title.trim();
        sb.append("# ").append(title).append("\n\n");

        sb.append("## ").append(SECTION_VISION).append("\n");
        String vision = project.vision == null ? "" : project.vision.trim();
        if (!vision.isEmpty()) sb.append(vision).append("\n");
        sb.append("\n");

        sb.append("## ").append(SECTION_OBJECTIFS).append("\n");
        if (project.objectives.isEmpty()) {
            sb.append("\n");
        } else {
            for (String o : project.objectives) {
                if (o == null || o.trim().isEmpty()) continue;
                sb.append("- ").append(o.trim()).append("\n");
            }
            sb.append("\n");
        }

        sb.append("## ").append(SECTION_DECISIONS).append("\n");
        boolean anyConfirmed = false;
        for (BureauProject.Decision d : project.decisions) {
            if (d == null || d.confidence != BureauProject.Confidence.CONFIRMED) continue;
            anyConfirmed = true;
            sb.append("- ✓ [").append(formatDate(d.createdAt)).append("] ")
                    .append(nz(d.text)).append("\n");
        }
        if (!anyConfirmed) sb.append("\n");
        else sb.append("\n");

        sb.append("## ").append(SECTION_HYPOTHESES).append("\n");
        boolean anyHyp = false;
        for (BureauProject.Decision d : project.decisions) {
            if (d == null || d.confidence != BureauProject.Confidence.HYPOTHESIS) continue;
            anyHyp = true;
            sb.append("- ? ").append(nz(d.text)).append("\n");
        }
        if (!anyHyp) sb.append("\n");
        else sb.append("\n");

        sb.append("## ").append(SECTION_A_VERIFIER).append("\n");
        boolean anyVerify = false;
        for (BureauProject.Decision d : project.decisions) {
            if (d == null || d.confidence != BureauProject.Confidence.TO_VERIFY) continue;
            anyVerify = true;
            sb.append("- ! ").append(nz(d.text)).append("\n");
        }
        if (!anyVerify) sb.append("\n");
        else sb.append("\n");

        sb.append("## ").append(SECTION_TACHES).append("\n");
        if (project.tasks.isEmpty()) {
            sb.append("\n");
        } else {
            for (BureauProject.Task t : project.tasks) {
                if (t == null) continue;
                sb.append("- [").append(t.done ? "x" : " ").append("] ")
                        .append(nz(t.text)).append("\n");
            }
            sb.append("\n");
        }

        sb.append("## ").append(SECTION_QUESTIONS).append("\n");
        if (project.openQuestions.isEmpty()) {
            sb.append("\n");
        } else {
            for (BureauProject.OpenQuestion q : project.openQuestions) {
                if (q == null || q.text == null || q.text.trim().isEmpty()) continue;
                sb.append("- ").append(q.text.trim()).append("\n");
            }
            sb.append("\n");
        }

        sb.append("## ").append(SECTION_REFERENCES).append("\n");
        if (project.references.isEmpty()) {
            sb.append("\n");
        } else {
            for (BureauProject.Reference r : project.references) {
                if (r == null) continue;
                String label = r.title == null || r.title.trim().isEmpty()
                        ? r.path : r.title.trim();
                String path = r.path == null ? "" : r.path.trim();
                if (path.startsWith("research/")) {
                    sb.append("- [").append(label).append("](../").append(path).append(")\n");
                } else if (!path.isEmpty()) {
                    sb.append("- [").append(label).append("](").append(path).append(")\n");
                } else {
                    sb.append("- ").append(label).append("\n");
                }
            }
            sb.append("\n");
        }

        sb.append("## ").append(SECTION_HISTORIQUE).append("\n");
        if (project.history.isEmpty()) {
            sb.append("\n");
        } else {
            for (BureauProject.HistoryEntry h : project.history) {
                if (h == null || h.text == null || h.text.trim().isEmpty()) continue;
                sb.append("- ").append(formatDate(h.createdAt)).append(" — ")
                        .append(h.text.trim()).append("\n");
            }
            sb.append("\n");
        }

        return sb.toString().trim() + "\n";
    }

    public static String formatDate(long epochMs) {
        long t = epochMs > 0 ? epochMs : System.currentTimeMillis();
        return new SimpleDateFormat("dd/MM/yyyy", Locale.FRENCH).format(new Date(t));
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }
}
