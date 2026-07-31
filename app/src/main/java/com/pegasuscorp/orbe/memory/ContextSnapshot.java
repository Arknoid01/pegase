package com.pegasuscorp.orbe.memory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Ce qui a vraiment été injecté dans le prompt pour un tour —
 * pour la lisibilité (🔍 / Portrait), pas un nouveau store.
 */
public final class ContextSnapshot {

    public final String promptText;
    public final String intent;
    public final List<String> memories;
    public final List<String> atlasEntities;
    public final List<String> profileSections;
    public final List<String> namedContexts;
    public final String sessionTopic;
    /** App écran copilote injectée (carte 🔍) — vide hors canal COPILOT. */
    public final String screenContextLabel;

    public ContextSnapshot(String promptText, String intent,
            List<String> memories, List<String> atlasEntities,
            List<String> profileSections, List<String> namedContexts,
            String sessionTopic) {
        this(promptText, intent, memories, atlasEntities, profileSections, namedContexts,
                sessionTopic, "");
    }

    public ContextSnapshot(String promptText, String intent,
            List<String> memories, List<String> atlasEntities,
            List<String> profileSections, List<String> namedContexts,
            String sessionTopic, String screenContextLabel) {
        this.promptText = promptText != null ? promptText : "";
        this.intent = intent != null ? intent : "general";
        this.memories = freeze(memories);
        this.atlasEntities = freeze(atlasEntities);
        this.profileSections = freeze(profileSections);
        this.namedContexts = freeze(namedContexts);
        this.sessionTopic = sessionTopic != null ? sessionTopic : "";
        this.screenContextLabel = screenContextLabel != null ? screenContextLabel : "";
    }

    public static ContextSnapshot empty() {
        return new ContextSnapshot("", "general", null, null, null, null, "", "");
    }

    public int memoryCount() {
        return memories.size();
    }

    private static List<String> freeze(List<String> src) {
        if (src == null || src.isEmpty()) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        for (String s : src) {
            if (s != null && !s.trim().isEmpty()) out.add(s.trim());
        }
        return Collections.unmodifiableList(out);
    }
}
