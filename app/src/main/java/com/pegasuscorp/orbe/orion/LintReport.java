package com.pegasuscorp.orbe.orion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Rapport de lint normalisé (réponse {@code GET /lint} du fileserver pod).
 */
public final class LintReport {

    public final String file;
    public final String tool;
    public final boolean ok;
    public final boolean toolMissing;
    public final List<LintIssue> issues;

    public LintReport(String file, String tool, boolean ok, boolean toolMissing,
            List<LintIssue> issues) {
        this.file = file != null ? file : "";
        this.tool = tool != null ? tool : "";
        this.ok = ok;
        this.toolMissing = toolMissing;
        if (issues == null || issues.isEmpty()) {
            this.issues = Collections.emptyList();
        } else {
            this.issues = Collections.unmodifiableList(new ArrayList<>(issues));
        }
    }

    public static LintReport empty(String file) {
        return new LintReport(file, "", true, false, Collections.emptyList());
    }

    public int errorCount() {
        int n = 0;
        for (LintIssue i : issues) {
            if (i != null && i.isError()) n++;
        }
        return n;
    }

    public int warningCount() {
        int n = 0;
        for (LintIssue i : issues) {
            if (i != null && !i.isError()) n++;
        }
        return n;
    }

    public boolean hasVisibleIssues() {
        return !toolMissing && !issues.isEmpty();
    }

    public static final class LintIssue {
        public final int line;
        public final int column;
        public final String severity;
        public final String rule;
        public final String message;

        public LintIssue(int line, int column, String severity, String rule, String message) {
            this.line = Math.max(0, line);
            this.column = Math.max(0, column);
            this.severity = severity != null ? severity : "warning";
            this.rule = rule != null ? rule : "";
            this.message = message != null ? message : "";
        }

        public boolean isError() {
            return "error".equalsIgnoreCase(severity);
        }

        public String summaryLine() {
            StringBuilder sb = new StringBuilder();
            if (line > 0) sb.append("L").append(line);
            if (column > 0) sb.append(":").append(column);
            if (sb.length() > 0) sb.append(' ');
            if (!rule.isEmpty()) sb.append('[').append(rule).append("] ");
            sb.append(message);
            return sb.toString().trim();
        }
    }
}
