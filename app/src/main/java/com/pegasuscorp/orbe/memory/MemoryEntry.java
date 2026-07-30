package com.pegasuscorp.orbe.memory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
    /** Liens vers l'atlas {@link EntityStore} (graphe mémoire). */
    public final List<String> entityIds = new ArrayList<>();
    /** Clés stables de souvenirs liés (1-hop, graphe léger). */
    public final List<String> relatedMemoryKeys = new ArrayList<>();

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

    public String memoryKey() {
        return com.pegasuscorp.orbe.rag.VectorStore.keyFor(category, content);
    }

    public JSONObject toJson() throws Exception {
        JSONObject o = new JSONObject()
                .put("category", category)
                .put("content", content)
                .put("importance", importance)
                .put("createdAt", createdAt)
                .put("source", source != null ? source : SOURCE_USER);
        if (!entityIds.isEmpty()) {
            o.put("entityIds", toJsonArray(entityIds));
        }
        if (!relatedMemoryKeys.isEmpty()) {
            o.put("relatedMemoryKeys", toJsonArray(relatedMemoryKeys));
        }
        return o;
    }

    public static MemoryEntry fromJson(JSONObject o) {
        MemoryEntry entry = new MemoryEntry(
                o.optString("category", "general"),
                o.optString("content", ""),
                o.optDouble("importance", 0.5),
                o.optString("createdAt", ""),
                o.optString("source", SOURCE_USER));
        readStringList(o.optJSONArray("entityIds"), entry.entityIds);
        readStringList(o.optJSONArray("relatedMemoryKeys"), entry.relatedMemoryKeys);
        return entry;
    }

    private static JSONArray toJsonArray(List<String> items) {
        JSONArray arr = new JSONArray();
        for (String item : items) {
            if (item != null && !item.isEmpty()) arr.put(item);
        }
        return arr;
    }

    private static void readStringList(JSONArray arr, List<String> out) {
        out.clear();
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            String s = arr.optString(i, "").trim();
            if (!s.isEmpty() && !out.contains(s)) out.add(s);
        }
    }

    /** Copie défensive pour les tests. */
    public List<String> getEntityIds() {
        return Collections.unmodifiableList(entityIds);
    }
}
