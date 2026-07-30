package com.pegasuscorp.orbe.memory;

import org.json.JSONObject;

/** Arête typée entre deux entités de l'atlas. */
public final class EntityEdge {

    public static final String TYPE_RELATED_TO = "related_to";
    public static final String TYPE_PART_OF = "part_of";
    public static final String TYPE_PREFERS = "prefers";
    public static final String TYPE_WORKS_ON = "works_on";
    public static final String TYPE_RUNS_ON = "runs_on";

    public final String fromId;
    public final String toId;
    public final String type;

    public EntityEdge(String fromId, String toId, String type) {
        this.fromId = fromId != null ? fromId : "";
        this.toId = toId != null ? toId : "";
        this.type = type != null && !type.isEmpty() ? type : TYPE_RELATED_TO;
    }

    public JSONObject toJson() throws Exception {
        return new JSONObject()
                .put("from", fromId)
                .put("to", toId)
                .put("type", type);
    }

    public static EntityEdge fromJson(JSONObject o) {
        return new EntityEdge(
                o.optString("from", ""),
                o.optString("to", ""),
                o.optString("type", TYPE_RELATED_TO));
    }

    /** Clé non orientée pour déduplication. */
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
}
