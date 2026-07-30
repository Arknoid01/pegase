package com.pegasuscorp.orbe.orion;

import java.util.Collections;
import java.util.List;

/** Plan d'exécution validé avant toute action sur une mission MASSIVE. */
public final class ExecutionPlan {

    public enum PlanStatus { PENDING, APPROVED, REJECTED }

    public final String title;
    public final List<PlanStep> steps;
    public final TaskRisk globalRisk;
    public PlanStatus status;

    public ExecutionPlan(String title, List<PlanStep> steps, TaskRisk globalRisk) {
        this.title = title != null ? title.trim() : "Mission";
        this.steps = steps == null || steps.isEmpty()
                ? Collections.emptyList()
                : Collections.unmodifiableList(steps);
        this.globalRisk = globalRisk != null ? globalRisk : TaskRisk.MEDIUM;
        this.status = PlanStatus.PENDING;
    }

    public String toReadableText() {
        StringBuilder sb = new StringBuilder();
        sb.append("📋 Plan : ").append(title).append("\n\n");
        for (PlanStep s : steps) {
            sb.append(s.index).append(". ").append(s.summary).append('\n');
            sb.append("   Fichier : ").append(s.targetFile != null
                    ? s.targetFile : "à déterminer").append('\n');
            if (s.risk == TaskRisk.CRITICAL) {
                sb.append("   ⚠️ Fichier critique\n");
            }
            sb.append('\n');
        }
        sb.append("Risque global : ").append(globalRisk).append('\n');
        sb.append("On y va dans cet ordre ?");
        return sb.toString();
    }
}
