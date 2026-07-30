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

    /** Jours sans usage avant le début de l'oubli naturel. */
    static final double GRACE_DAYS = 14;
    /** Demi-vie de l'oubli (jours au-delà de la grâce) — très lent. */
    static final double DECAY_HALF_LIFE_DAYS = 365;
    static final double MIN_WEIGHT = 0.05;

    public final String fromId;
    public final String toId;
    public final String type;
    /** Force du lien [0, 1] — utilisée pour le boost graphe à la récupération. */
    public final double weight;
    /** Connaissance stable : pas d'oubli naturel. */
    public final boolean frozen;
    /** Dernière utilisation (inférence mémoire ou récupération). */
    public final long lastUsedAtMs;
    /** Poids au moment de la dernière utilisation (référence pour l'oubli). */
    public final double weightAtLastUse;

    public EntityEdge(String fromId, String toId, String type) {
        this(fromId, toId, type, defaultWeight(type), defaultFrozen(type), System.currentTimeMillis());
    }

    public EntityEdge(String fromId, String toId, String type, double weight) {
        this(fromId, toId, type, weight, defaultFrozen(type), System.currentTimeMillis());
    }

    public EntityEdge(String fromId, String toId, String type, double weight, boolean frozen) {
        this(fromId, toId, type, weight, frozen, System.currentTimeMillis());
    }

    public EntityEdge(String fromId, String toId, String type, double weight, boolean frozen,
            long lastUsedAtMs) {
        this(fromId, toId, type, weight, frozen, lastUsedAtMs, weight);
    }

    public EntityEdge(String fromId, String toId, String type, double weight, boolean frozen,
            long lastUsedAtMs, double weightAtLastUse) {
        this.fromId = fromId != null ? fromId : "";
        this.toId = toId != null ? toId : "";
        this.type = type != null && !type.isEmpty() ? type : TYPE_RELATED_TO;
        this.weight = clampWeight(weight);
        this.frozen = frozen;
        this.lastUsedAtMs = lastUsedAtMs > 0 ? lastUsedAtMs : System.currentTimeMillis();
        this.weightAtLastUse = clampWeight(weightAtLastUse > 0 ? weightAtLastUse : weight);
    }

    public JSONObject toJson() throws Exception {
        JSONObject o = new JSONObject()
                .put("from", fromId)
                .put("to", toId)
                .put("type", type)
                .put("weight", weight)
                .put("lastUsedAt", lastUsedAtMs)
                .put("weightAtLastUse", weightAtLastUse);
        if (frozen) o.put("frozen", true);
        return o;
    }

    public static EntityEdge fromJson(JSONObject o) {
        String type = o.optString("type", TYPE_RELATED_TO);
        double weight = o.has("weight") ? o.optDouble("weight", defaultWeight(type))
                : defaultWeight(type);
        boolean frozen = o.optBoolean("frozen", defaultFrozen(type));
        long lastUsed = o.optLong("lastUsedAt", System.currentTimeMillis());
        double atLastUse = o.optDouble("weightAtLastUse", weight);
        return new EntityEdge(
                o.optString("from", ""),
                o.optString("to", ""),
                type,
                weight,
                frozen,
                lastUsed,
                atLastUse);
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

    /** Types structurels considérés stables par défaut. */
    public static boolean defaultFrozen(String type) {
        return TYPE_RUNS_ON.equals(type) || TYPE_PART_OF.equals(type);
    }

    static double clampWeight(double weight) {
        if (weight < MIN_WEIGHT) return MIN_WEIGHT;
        if (weight > 1) return 1;
        return weight;
    }

    /** Poids après oubli naturel selon le temps écoulé depuis la dernière utilisation. */
    static double decayedWeight(double weightAtLastUse, long lastUsedAtMs, long nowMs,
            boolean frozen) {
        if (frozen || weightAtLastUse <= MIN_WEIGHT) return weightAtLastUse;
        double daysUnused = (nowMs - lastUsedAtMs) / 86_400_000.0;
        if (daysUnused <= GRACE_DAYS) return weightAtLastUse;
        double excessDays = daysUnused - GRACE_DAYS;
        double factor = Math.pow(0.5, excessDays / DECAY_HALF_LIFE_DAYS);
        return clampWeight(weightAtLastUse * factor);
    }

    EntityEdge withWeight(double newWeight) {
        return new EntityEdge(fromId, toId, type, newWeight, frozen, lastUsedAtMs, weightAtLastUse);
    }

    EntityEdge withWeightAndTouch(double newWeight, long nowMs) {
        return new EntityEdge(fromId, toId, type, newWeight, frozen, nowMs, newWeight);
    }

    EntityEdge touched(long nowMs, double strengthenDelta) {
        double w = Math.min(1.0, weight + strengthenDelta);
        return new EntityEdge(fromId, toId, type, w, frozen, nowMs, w);
    }

    EntityEdge decayedTo(long nowMs) {
        double w = decayedWeight(weightAtLastUse, lastUsedAtMs, nowMs, frozen);
        if (Math.abs(w - weight) < 0.0001) return this;
        return new EntityEdge(fromId, toId, type, w, frozen, lastUsedAtMs, weightAtLastUse);
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
