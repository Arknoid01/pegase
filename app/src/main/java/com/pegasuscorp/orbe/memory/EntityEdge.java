package com.pegasuscorp.orbe.memory;

import org.json.JSONObject;

import java.util.Locale;

/** Arête typée et pondérée entre deux entités de l'atlas. */
public final class EntityEdge {

    public static final String TYPE_RELATED_TO = "related_to";
    public static final String TYPE_PART_OF = "part_of";
    public static final String TYPE_PREFERS = "prefers";
    public static final String TYPE_WORKS_ON = "works_on";
    public static final String TYPE_RUNS_ON = "runs_on";

    public final String fromId;
    public final String toId;
    public final String type;
    /** Force du lien [0, 1] — utilisée pour le boost graphe à la récupération. */
    public final double weight;

    public EntityEdge(String fromId, String toId, String type) {
        this(fromId, toId, type, defaultWeight(type));
    }

    public EntityEdge(String fromId, String toId, String type, double weight) {
        this.fromId = fromId != null ? fromId : "";
        this.toId = toId != null ? toId : "";
        this.type = type != null && !type.isEmpty() ? type : TYPE_RELATED_TO;
        this.weight = clampWeight(weight);
    }

    public JSONObject toJson() throws Exception {
        return new JSONObject()
                .put("from", fromId)
                .put("to", toId)
                .put("type", type)
                .put("weight", weight);
    }

    public static EntityEdge fromJson(JSONObject o) {
        String type = o.optString("type", TYPE_RELATED_TO);
        double weight = o.has("weight") ? o.optDouble("weight", defaultWeight(type))
                : defaultWeight(type);
        return new EntityEdge(
                o.optString("from", ""),
                o.optString("to", ""),
                type,
                weight);
    }

    public static double defaultWeight(String type) {
        if (type == null) return 0.70;
        switch (type) {
            case TYPE_RUNS_ON: return 0.95;
            case TYPE_WORKS_ON: return 0.90;
            case TYPE_PART_OF: return 0.85;
            case TYPE_RELATED_TO: return 0.75;
            case TYPE_PREFERS: return 0.55;
            default: return 0.70;
        }
    }

    static double clampWeight(double weight) {
        if (weight < 0) return 0;
        if (weight > 1) return 1;
        return weight;
    }

    /** Clé non orientée pour déduplication (type inclus). */
    public String undirectedKey() {
        if (fromId.compareTo(toId) <= 0) {
            return fromId + "|" + type + "|" + toId;
        }
        return toId + "|" + type + "|" + fromId;
    }

    public static String labelFr(String type) {
        if (type == null) return "lié à";
        switch (type) {
            case TYPE_PART_OF: return "partie de";
            case TYPE_PREFERS: return "préfère";
            case TYPE_WORKS_ON: return "travaille sur";
            case TYPE_RUNS_ON: return "tourne sur";
            case TYPE_RELATED_TO:
            default: return "lié à";
        }
    }

    public static String formatWeight(double weight) {
        return String.format(Locale.ROOT, "%.2f", clampWeight(weight));
    }
}
