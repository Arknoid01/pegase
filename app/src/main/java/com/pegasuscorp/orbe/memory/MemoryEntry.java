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
    /** Connaissance stable : pas d'oubli naturel sur l'importance. */
    public boolean frozen;
    /** Dernière récupération ou consolidation. */
    public long lastUsedAtMs;
    /** Importance au moment de la dernière utilisation (référence pour l'oubli). */
    public double importanceAtLastUse;

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
        this.frozen = MemoryVitality.defaultFrozen(category);
        long now = System.currentTimeMillis();
        this.lastUsedAtMs = now;
        this.importanceAtLastUse = importance;
    }

    public boolean isFallbackSource() {
        return SOURCE_FALLBACK.equals(source);
    }

    /** Importance effective après oubli naturel. */
    public double effectiveImportance() {
        return effectiveImportance(System.currentTimeMillis());
    }

    public double effectiveImportance(long nowMs) {
        return MemoryVitality.decayedImportance(importanceAtLastUse, lastUsedAtMs, nowMs, frozen);
    }

    /** Applique l'oubli naturel sur le champ {@link #importance}. */
    public boolean applyDecay(long nowMs) {
        double effective = effectiveImportance(nowMs);
        if (Math.abs(effective - importance) < 0.0001) return false;
        importance = effective;
        return true;
    }

    /** Renforce légèrement après une récupération réussie. */
    public void touchRetrieval(long nowMs) {
        double w = Math.min(1.0, importance + MemoryVitality.RETRIEVAL_STRENGTHEN);
        importance = w;
        importanceAtLastUse = w;
        lastUsedAtMs = nowMs;
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
                .put("source", source != null ? source : SOURCE_USER)
                .put("lastUsedAt", lastUsedAtMs)
                .put("importanceAtLastUse", importanceAtLastUse);
        if (frozen) o.put("frozen", true);
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
        entry.frozen = o.optBoolean("frozen", MemoryVitality.defaultFrozen(entry.category));
        entry.lastUsedAtMs = o.optLong("lastUsedAt", entry.lastUsedAtMs);
        entry.importanceAtLastUse = o.optDouble("importanceAtLastUse", entry.importance);
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
