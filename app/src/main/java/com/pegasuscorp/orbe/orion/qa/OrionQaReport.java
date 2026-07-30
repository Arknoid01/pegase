package com.pegasuscorp.orbe.orion.qa;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Résultat QA Orion — conforme ou non + contraintes à rajouter. */
public final class OrionQaReport {

    public enum Verdict {
        COMPLIANT,
        NON_COMPLIANT
    }

    public final Verdict verdict;
    public final String reason;
    public final List<String> extraExclusions;
    public final String diffSummary;
    public final boolean fromStructural;
    public final boolean fromSemantic;

    public OrionQaReport(Verdict verdict, String reason, List<String> extraExclusions,
            String diffSummary, boolean fromStructural, boolean fromSemantic) {
        this.verdict = verdict;
        this.reason = reason != null ? reason : "";
        this.extraExclusions = extraExclusions == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(extraExclusions));
        this.diffSummary = diffSummary != null ? diffSummary : "";
        this.fromStructural = fromStructural;
        this.fromSemantic = fromSemantic;
    }

    public boolean isCompliant() {
        return verdict == Verdict.COMPLIANT;
    }

    public static OrionQaReport compliant(String diffSummary) {
        return new OrionQaReport(Verdict.COMPLIANT, "Conforme à la mission.",
                Collections.emptyList(), diffSummary, true, true);
    }

    public static OrionQaReport nonCompliant(String reason, List<String> exclusions,
            String diffSummary, boolean structural, boolean semantic) {
        return new OrionQaReport(Verdict.NON_COMPLIANT, reason, exclusions,
                diffSummary, structural, semantic);
    }
}
