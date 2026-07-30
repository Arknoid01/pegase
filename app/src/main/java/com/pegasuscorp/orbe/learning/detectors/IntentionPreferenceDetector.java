package com.pegasuscorp.orbe.learning.detectors;

import android.content.Context;
import android.text.TextUtils;

import com.pegasuscorp.orbe.intentions.IntentionIds;
import com.pegasuscorp.orbe.intentions.IntentionPrefs;
import com.pegasuscorp.orbe.learning.LearningCandidate;
import com.pegasuscorp.orbe.learning.Observation;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Refus répétés → suppress ; accepts répétés (drive / life) → boost earlier (PENDING).
 */
public final class IntentionPreferenceDetector implements PatternDetector {

    public static final int MIN_NEGATIVES = 3;
    public static final int MIN_ACCEPTS = 3;
    public static final long WINDOW_MS = 30L * 24L * 60L * 60L * 1000L;

    @Override
    public LearningCandidate detect(Context ctx, List<Observation> observations) {
        if (ctx == null || observations == null) return null;
        long since = System.currentTimeMillis() - WINDOW_MS;
        Map<String, Integer> negatives = new HashMap<>();
        Map<String, Integer> accepts = new HashMap<>();
        for (Observation o : observations) {
            if (!Observation.TYPE_INTENTION_FEEDBACK.equals(o.type)) continue;
            if (o.atMs < since) continue;
            String id = o.payload.optString("intentionId", "");
            String action = o.payload.optString("action", "");
            if (TextUtils.isEmpty(id) || !IntentionIds.isValid(id)) continue;
            if (IntentionIds.ACTION_NEVER.equals(action)
                    || IntentionIds.ACTION_IGNORE_TODAY.equals(action)
                    || IntentionIds.ACTION_SNOOZE.equals(action)) {
                negatives.put(id, negatives.getOrDefault(id, 0) + 1);
            } else if (IntentionIds.ACTION_ACCEPT.equals(action) && isBoostable(id)) {
                accepts.put(id, accepts.getOrDefault(id, 0) + 1);
            }
        }

        LearningCandidate suppress = bestSuppress(ctx, negatives);
        if (suppress != null) return suppress;
        return bestBoost(ctx, accepts);
    }

    private static boolean isBoostable(String id) {
        return IntentionIds.DRIVE_BT.equals(id)
                || (id != null && id.startsWith("life:"));
    }

    private static LearningCandidate bestSuppress(Context ctx, Map<String, Integer> negatives) {
        String bestId = null;
        int bestCount = 0;
        for (Map.Entry<String, Integer> e : negatives.entrySet()) {
            if (e.getValue() < MIN_NEGATIVES) continue;
            if (IntentionPrefs.isSuppressed(ctx, e.getKey())) continue;
            if (e.getValue() > bestCount) {
                bestCount = e.getValue();
                bestId = e.getKey();
            }
        }
        if (bestId == null) return null;
        double conf = Math.min(0.95, 0.55 + 0.1 * bestCount);
        String label = IntentionIds.displayName(bestId);
        String reason = String.format(Locale.FRANCE,
                "Tu as écarté « %s » %d fois récemment. J'arrête de te le proposer ?",
                label, bestCount);
        JSONObject proposal;
        try {
            proposal = new JSONObject().put("suppress", true).put("intentionId", bestId);
        } catch (Exception e) {
            proposal = new JSONObject();
        }
        return candidate(LearningCandidate.TYPE_INTENTION_SUPPRESS, bestId, proposal,
                bestCount, conf, reason);
    }

    private static LearningCandidate bestBoost(Context ctx, Map<String, Integer> accepts) {
        String bestId = null;
        int bestCount = 0;
        for (Map.Entry<String, Integer> e : accepts.entrySet()) {
            if (e.getValue() < MIN_ACCEPTS) continue;
            if (IntentionPrefs.isSuppressed(ctx, e.getKey())) continue;
            if (IntentionPrefs.prefersEarlier(ctx, e.getKey())) continue;
            if (e.getValue() > bestCount) {
                bestCount = e.getValue();
                bestId = e.getKey();
            }
        }
        if (bestId == null) return null;
        double conf = Math.min(0.9, 0.5 + 0.1 * bestCount);
        String label = IntentionIds.displayName(bestId);
        String reason = String.format(Locale.FRANCE,
                "Tu acceptes souvent « %s » (%d×). Je te le propose un peu plus tôt ?",
                label, bestCount);
        JSONObject proposal;
        try {
            proposal = new JSONObject().put("boost", true).put("intentionId", bestId);
        } catch (Exception e) {
            proposal = new JSONObject();
        }
        return candidate(LearningCandidate.TYPE_INTENTION_BOOST, bestId, proposal,
                bestCount, conf, reason);
    }

    private static LearningCandidate candidate(String type, String targetId, JSONObject proposal,
            int evidence, double conf, String reason) {
        long now = System.currentTimeMillis();
        return new LearningCandidate(
                "", type, targetId, proposal, evidence, conf, reason,
                LearningCandidate.STATUS_PENDING, now, now, 0L);
    }
}
