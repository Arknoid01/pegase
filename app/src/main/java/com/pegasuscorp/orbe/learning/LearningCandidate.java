package com.pegasuscorp.orbe.learning;

import org.json.JSONObject;

/**
 * Hypothèse d'apprentissage — jamais appliquée sans validation utilisateur (V1).
 */
public final class LearningCandidate {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_ACCEPTED = "ACCEPTED";
    public static final String STATUS_REFUSED = "REFUSED";
    public static final String STATUS_SNOOZED = "SNOOZED";

    public static final String TYPE_INTENTION_SUPPRESS = "intention_suppress_suggest";
    public static final String TYPE_INTENTION_BOOST = "intention_boost_suggest";
    public static final String TYPE_ROUTINE_TIME_SHIFT = "routine_time_shift";

    public final String id;
    public final String type;
    public final String targetId;
    public final JSONObject proposal;
    public final int evidenceCount;
    public final double confidence;
    public final String reason;
    public final String status;
    public final long updatedAtMs;
    public final long createdAtMs;
    /** Snooze / refuse cooldown until. */
    public final long quietUntilMs;

    public LearningCandidate(String id, String type, String targetId, JSONObject proposal,
            int evidenceCount, double confidence, String reason, String status,
            long updatedAtMs, long createdAtMs, long quietUntilMs) {
        this.id = id == null ? "" : id;
        this.type = type == null ? "" : type;
        this.targetId = targetId == null ? "" : targetId;
        this.proposal = proposal != null ? proposal : new JSONObject();
        this.evidenceCount = Math.max(0, evidenceCount);
        this.confidence = confidence;
        this.reason = reason == null ? "" : reason;
        this.status = status == null ? STATUS_PENDING : status;
        this.updatedAtMs = updatedAtMs;
        this.createdAtMs = createdAtMs;
        this.quietUntilMs = quietUntilMs;
    }

    public boolean isPending() {
        return STATUS_PENDING.equals(status);
    }

    public LearningCandidate withStatus(String newStatus, long nowMs) {
        return new LearningCandidate(id, type, targetId, proposal, evidenceCount, confidence,
                reason, newStatus, nowMs, createdAtMs, quietUntilMs);
    }

    public LearningCandidate withQuietUntil(long untilMs, String newStatus, long nowMs) {
        return new LearningCandidate(id, type, targetId, proposal, evidenceCount, confidence,
                reason, newStatus, nowMs, createdAtMs, untilMs);
    }

    public String fingerprint() {
        String prop = proposal != null ? proposal.toString() : "";
        return type + "|" + targetId + "|" + prop;
    }

    public String title() {
        if (TYPE_ROUTINE_TIME_SHIFT.equals(type)) return "Ajuster un rythme";
        if (TYPE_INTENTION_SUPPRESS.equals(type)) return "Suggestion trop fréquente";
        if (TYPE_INTENTION_BOOST.equals(type)) return "Suggestion plus tôt";
        return "Apprentissage";
    }

    public String body() {
        if (!reason.isEmpty()) return reason;
        return "Pégase a une hypothèse à te proposer.";
    }

    public JSONObject toJson() throws Exception {
        return new JSONObject()
                .put("id", id)
                .put("type", type)
                .put("targetId", targetId)
                .put("proposal", proposal)
                .put("evidenceCount", evidenceCount)
                .put("confidence", confidence)
                .put("reason", reason)
                .put("status", status)
                .put("updatedAtMs", updatedAtMs)
                .put("createdAtMs", createdAtMs)
                .put("quietUntilMs", quietUntilMs);
    }

    public static LearningCandidate fromJson(JSONObject o) {
        if (o == null) return null;
        JSONObject proposal = o.optJSONObject("proposal");
        if (proposal == null) proposal = new JSONObject();
        return new LearningCandidate(
                o.optString("id", ""),
                o.optString("type", ""),
                o.optString("targetId", ""),
                proposal,
                o.optInt("evidenceCount", 0),
                o.optDouble("confidence", 0),
                o.optString("reason", ""),
                o.optString("status", STATUS_PENDING),
                o.optLong("updatedAtMs", 0L),
                o.optLong("createdAtMs", 0L),
                o.optLong("quietUntilMs", 0L));
    }
}
