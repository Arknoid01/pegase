package com.pegasuscorp.orbe.orion;

/** Une étape d'un {@link ExecutionPlan} (mission MASSIVE). */
public final class PlanStep {

    public final int index;
    public final String summary;
    public String targetFile;
    public final String keyword;
    public TaskRisk risk;
    public boolean done;

    public PlanStep(int index, String summary, String targetFile,
            String keyword, TaskRisk risk) {
        this.index = Math.max(1, index);
        this.summary = summary != null ? summary.trim() : "";
        this.targetFile = normalizeFile(targetFile);
        this.keyword = normalizeKeyword(keyword);
        this.risk = risk != null ? risk : TaskRisk.MEDIUM;
        this.done = false;
    }

    private static String normalizeFile(String v) {
        if (v == null) return null;
        String t = v.trim();
        if (t.isEmpty() || "null".equalsIgnoreCase(t)) return null;
        return t;
    }

    private static String normalizeKeyword(String v) {
        if (v == null) return null;
        String t = v.trim();
        if (t.isEmpty() || "null".equalsIgnoreCase(t)) return null;
        return t;
    }
}
