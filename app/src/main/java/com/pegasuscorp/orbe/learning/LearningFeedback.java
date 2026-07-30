package com.pegasuscorp.orbe.learning;

import android.content.Context;
import android.text.TextUtils;
import android.widget.Toast;

import com.pegasuscorp.orbe.intentions.IntentionIds;
import com.pegasuscorp.orbe.intentions.IntentionPrefs;
import com.pegasuscorp.orbe.life.LifePatternStore;

import org.json.JSONObject;

/**
 * Applique Oui / Non / Plus tard sur un {@link LearningCandidate}.
 */
public final class LearningFeedback {

    private LearningFeedback() {}

    public static void accept(Context ctx, String candidateId) {
        if (ctx == null || TextUtils.isEmpty(candidateId)) return;
        Context app = ctx.getApplicationContext();
        LearningCandidateStore store = LearningCandidateStore.getInstance(app);
        LearningCandidate c = store.get(candidateId);
        if (c == null || !c.isPending()) return;
        long now = System.currentTimeMillis();
        boolean ok = apply(app, c);
        store.replace(candidateId, c.withStatus(LearningCandidate.STATUS_ACCEPTED, now));
        Toast.makeText(app, ok ? "C'est noté" : "Hypothèse enregistrée", Toast.LENGTH_SHORT).show();
    }

    public static void refuse(Context ctx, String candidateId) {
        if (ctx == null || TextUtils.isEmpty(candidateId)) return;
        Context app = ctx.getApplicationContext();
        LearningCandidateStore store = LearningCandidateStore.getInstance(app);
        LearningCandidate c = store.get(candidateId);
        if (c == null) return;
        long now = System.currentTimeMillis();
        store.replace(candidateId, c.withQuietUntil(
                LearningPolicy.refuseQuietUntil(), LearningCandidate.STATUS_REFUSED, now));
        Toast.makeText(app, "Hypothèse refusée", Toast.LENGTH_SHORT).show();
    }

    public static void snooze(Context ctx, String candidateId) {
        if (ctx == null || TextUtils.isEmpty(candidateId)) return;
        Context app = ctx.getApplicationContext();
        LearningCandidateStore store = LearningCandidateStore.getInstance(app);
        LearningCandidate c = store.get(candidateId);
        if (c == null) return;
        long now = System.currentTimeMillis();
        store.replace(candidateId, c.withQuietUntil(
                LearningPolicy.snoozeQuietUntil(), LearningCandidate.STATUS_SNOOZED, now));
        Toast.makeText(app, "Plus tard", Toast.LENGTH_SHORT).show();
    }

    private static boolean apply(Context app, LearningCandidate c) {
        try {
            if (LearningCandidate.TYPE_INTENTION_SUPPRESS.equals(c.type)) {
                String intentionId = c.targetId;
                if (!TextUtils.isEmpty(intentionId) && IntentionIds.isValid(intentionId)) {
                    IntentionPrefs.suppress(app, intentionId);
                    return true;
                }
            }
            if (LearningCandidate.TYPE_INTENTION_BOOST.equals(c.type)) {
                String intentionId = c.targetId;
                if (!TextUtils.isEmpty(intentionId) && IntentionIds.isValid(intentionId)) {
                    IntentionPrefs.setPreferEarlier(app, intentionId, true);
                    return true;
                }
            }
            if (LearningCandidate.TYPE_ROUTINE_TIME_SHIFT.equals(c.type)) {
                JSONObject p = c.proposal;
                int sh = p.optInt("startHour", -1);
                int sm = p.optInt("startMinute", 0);
                if (sh < 0) {
                    String start = p.optString("start", "");
                    int[] hm = parseHm(start);
                    if (hm != null) {
                        sh = hm[0];
                        sm = hm[1];
                    }
                }
                if (sh >= 0) {
                    return LifePatternStore.getInstance(app)
                            .shiftStartKeepingDuration(c.targetId, sh, sm);
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static int[] parseHm(String s) {
        if (TextUtils.isEmpty(s)) return null;
        String t = s.trim().replace('h', ':');
        String[] parts = t.split(":");
        if (parts.length < 1) return null;
        try {
            int h = Integer.parseInt(parts[0].trim());
            int m = parts.length > 1 && !parts[1].isEmpty()
                    ? Integer.parseInt(parts[1].trim()) : 0;
            if (h < 0 || h > 23 || m < 0 || m > 59) return null;
            return new int[]{h, m};
        } catch (Exception e) {
            return null;
        }
    }
}
