package com.pegasuscorp.orbe.memory;

import org.json.JSONObject;

/**
 * Souvenir permanent (profil, projets, préférences…).
 */
public class MemoryEntry {

    /** Ajouté hors backend fallback (défaut). */
    public static final String SOURCE_USER = "user";
    /** Ajouté par le modèle de repli (120b / Qwen) — non injecté dans le contexte par défaut. */
    public static final String SOURCE_FALLBACK = "fallback";

    public String category;
    public String content;
    public double importance;
    public String createdAt;
    /** {@link #SOURCE_USER} ou {@link #SOURCE_FALLBACK}. */
    public String source;

    public MemoryEntry(String category, String content, double importance, String createdAt) {
        this(category, content, importance, createdAt, SOURCE_USER);
    }

    public MemoryEntry(String category, String content, double importance, String createdAt,
            String source) {
        this.category = category;
        this.content = content;
        this.importance = importance;
        this.createdAt = createdAt;
        this.source = (source == null || source.isEmpty()) ? SOURCE_USER : source;
    }

    public boolean isFallbackSource() {
        return SOURCE_FALLBACK.equals(source);
    }

    public JSONObject toJson() throws Exception {
        return new JSONObject()
                .put("category", category)
                .put("content", content)
                .put("importance", importance)
                .put("createdAt", createdAt)
                .put("source", source != null ? source : SOURCE_USER);
    }

    public static MemoryEntry fromJson(JSONObject o) {
        return new MemoryEntry(
                o.optString("category", "general"),
                o.optString("content", ""),
                o.optDouble("importance", 0.5),
                o.optString("createdAt", ""),
                o.optString("source", SOURCE_USER));
    }
}
