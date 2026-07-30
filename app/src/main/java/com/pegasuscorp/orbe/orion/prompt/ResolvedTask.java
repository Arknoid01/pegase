package com.pegasuscorp.orbe.orion.prompt;

import com.pegasuscorp.orbe.orion.TaskComplexity;
import com.pegasuscorp.orbe.orion.TaskRisk;
import com.pegasuscorp.orbe.orion.search.FileLocation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Tâche déjà résolue — entrée unique du {@link PromptCompiler}.
 * Le compilateur n'invente pas de précisions : il formate seulement.
 */
public final class ResolvedTask {

    public final String mission;
    public final String context;
    public final String objective;
    public final List<String> actions;
    public final List<String> exclusions;
    public final List<String> validation;
    public final List<String> assumptions;
    public final List<String> investigate;
    public final String extractedKeyword;
    public final String rawInput;
    public final TaskComplexity complexity;
    public final TaskRisk risk;
    /** Emplacement ciblé (Phase 1 FileSearcher) — peut être null. */
    public final FileLocation fileLocation;
    /** PATCH ou FEATURE — fixé une fois, jamais redétecté sur du compilé. */
    public final OrionMode mode;

    public ResolvedTask(String mission, String context, String objective,
            List<String> actions, List<String> exclusions, List<String> validation,
            List<String> assumptions, List<String> investigate, String extractedKeyword,
            String rawInput, TaskComplexity complexity, TaskRisk risk) {
        this(mission, context, objective, actions, exclusions, validation,
                assumptions, investigate, extractedKeyword, rawInput, complexity, risk,
                null, OrionMode.PATCH);
    }

    public ResolvedTask(String mission, String context, String objective,
            List<String> actions, List<String> exclusions, List<String> validation,
            List<String> assumptions, List<String> investigate, String extractedKeyword,
            String rawInput, TaskComplexity complexity, TaskRisk risk,
            FileLocation fileLocation) {
        this(mission, context, objective, actions, exclusions, validation,
                assumptions, investigate, extractedKeyword, rawInput, complexity, risk,
                fileLocation, OrionMode.PATCH);
    }

    public ResolvedTask(String mission, String context, String objective,
            List<String> actions, List<String> exclusions, List<String> validation,
            List<String> assumptions, List<String> investigate, String extractedKeyword,
            String rawInput, TaskComplexity complexity, TaskRisk risk,
            FileLocation fileLocation, OrionMode mode) {
        this.mission = mission != null ? mission : "";
        this.context = context != null ? context : "";
        this.objective = objective != null ? objective : "";
        this.actions = freeze(actions);
        this.exclusions = freeze(exclusions);
        this.validation = freeze(validation);
        this.assumptions = freeze(assumptions);
        this.investigate = freeze(investigate);
        this.extractedKeyword = normalizeKeyword(extractedKeyword);
        this.rawInput = rawInput != null ? rawInput.trim() : "";
        this.complexity = complexity != null ? complexity : TaskComplexity.MEDIUM;
        this.risk = risk != null ? risk : TaskRisk.LOW;
        this.fileLocation = fileLocation;
        this.mode = mode != null ? mode : OrionMode.PATCH;
    }

    private static List<String> freeze(List<String> in) {
        if (in == null || in.isEmpty()) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        for (String s : in) {
            if (s != null && !s.trim().isEmpty()) out.add(s.trim());
        }
        return Collections.unmodifiableList(out);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String mission = "";
        private String context = "";
        private String objective = "";
        private String extractedKeyword = "";
        private String rawInput = "";
        private TaskComplexity complexity = TaskComplexity.MEDIUM;
        private TaskRisk risk = TaskRisk.LOW;
        private FileLocation fileLocation;
        private OrionMode mode = OrionMode.PATCH;
        private final List<String> actions = new ArrayList<>();
        private final List<String> exclusions = new ArrayList<>();
        private final List<String> validation = new ArrayList<>();
        private final List<String> assumptions = new ArrayList<>();
        private final List<String> investigate = new ArrayList<>();

        public Builder from(ResolvedTask task) {
            if (task == null) return this;
            mission = task.mission;
            context = task.context;
            objective = task.objective;
            extractedKeyword = task.extractedKeyword;
            rawInput = task.rawInput;
            complexity = task.complexity;
            risk = task.risk;
            fileLocation = task.fileLocation;
            mode = task.mode != null ? task.mode : OrionMode.PATCH;
            actions.clear();
            actions.addAll(task.actions);
            exclusions.clear();
            exclusions.addAll(task.exclusions);
            validation.clear();
            validation.addAll(task.validation);
            assumptions.clear();
            assumptions.addAll(task.assumptions);
            investigate.clear();
            investigate.addAll(task.investigate);
            return this;
        }

        public Builder mission(String v) { this.mission = v; return this; }
        public Builder context(String v) { this.context = v; return this; }
        public Builder objective(String v) { this.objective = v; return this; }
        public Builder rawInput(String v) { this.rawInput = v; return this; }
        public Builder complexity(TaskComplexity v) {
            if (v != null) this.complexity = v;
            return this;
        }
        public Builder risk(TaskRisk v) {
            if (v != null) this.risk = v;
            return this;
        }
        public Builder fileLocation(FileLocation loc) {
            this.fileLocation = loc;
            return this;
        }
        public Builder mode(OrionMode v) {
            if (v != null) this.mode = v;
            return this;
        }
        public Builder action(String v) { if (v != null) actions.add(v); return this; }
        public Builder exclusion(String v) { if (v != null) exclusions.add(v); return this; }
        public Builder validation(String v) { if (v != null) validation.add(v); return this; }
        public Builder assumption(String v) { if (v != null) assumptions.add(v); return this; }
        public Builder investigate(String v) { if (v != null) investigate.add(v); return this; }
        public Builder keyword(String v) { this.extractedKeyword = v; return this; }

        public ResolvedTask build() {
            return new ResolvedTask(mission, context, objective, actions, exclusions,
                    validation, assumptions, investigate, extractedKeyword,
                    rawInput, complexity, risk, fileLocation, mode);
        }
    }

    private static String normalizeKeyword(String v) {
        if (v == null) return "";
        String t = v.trim();
        if (t.isEmpty() || "null".equalsIgnoreCase(t) || "(null)".equalsIgnoreCase(t)) return "";
        return t;
    }
}
