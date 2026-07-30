package com.pegasuscorp.orbe.learning.detectors;

import android.content.Context;
import android.text.TextUtils;

import com.pegasuscorp.orbe.learning.LearningCandidate;
import com.pegasuscorp.orbe.learning.Observation;
import com.pegasuscorp.orbe.life.LifePatternStore;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Connexions BT pendant / près d'un rythme déclaré → proposer un décalage d'horaire.
 */
public final class RoutineTimeDetector implements PatternDetector {

    public static final int MIN_SAMPLES = 5;
    public static final int MIN_SHIFT_MINUTES = 10;
    public static final long WINDOW_MS = 45L * 24L * 60L * 60L * 1000L;
    /** Fenêtre autour de la plage déclarée (minutes). */
    public static final int NEAR_WINDOW_MIN = 45;

    @Override
    public LearningCandidate detect(Context ctx, List<Observation> observations) {
        if (ctx == null || observations == null) return null;
        long since = System.currentTimeMillis() - WINDOW_MS;
        Map<String, List<Integer>> minutesByPattern = new HashMap<>();
        for (Observation o : observations) {
            if (!Observation.TYPE_BT_ACL.equals(o.type)) continue;
            if (o.atMs < since) continue;
            if (!o.payload.optBoolean("connected", false)) continue;
            String patternId = o.payload.optString("duringPatternId", "");
            if (TextUtils.isEmpty(patternId)) continue;
            Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(o.atMs);
            int mins = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
            minutesByPattern.computeIfAbsent(patternId, k -> new ArrayList<>()).add(mins);
        }

        LifePatternStore store = LifePatternStore.getInstance(ctx);
        LearningCandidate best = null;
        for (LifePatternStore.LifePattern p : store.listAll()) {
            if (!p.active) continue;
            List<Integer> samples = minutesByPattern.get(p.id);
            if (samples == null || samples.size() < MIN_SAMPLES) continue;
            // garder ceux proches de la plage (± NEAR_WINDOW)
            int start = p.startHour * 60 + p.startMinute;
            List<Integer> near = new ArrayList<>();
            for (int m : samples) {
                if (minutesNear(m, start, NEAR_WINDOW_MIN)) near.add(m);
            }
            if (near.size() < MIN_SAMPLES) continue;
            int median = median(near);
            int shift = Math.abs(median - start);
            // wrap midnight: take shorter arc
            shift = Math.min(shift, 24 * 60 - shift);
            if (shift < MIN_SHIFT_MINUTES) continue;
            int newH = (median / 60) % 24;
            int newM = median % 60;
            double conf = Math.min(0.92, 0.5 + 0.05 * near.size());
            String reason = String.format(Locale.FRANCE,
                    "J'ai remarqué que tu commences plutôt « %s » vers %02d:%02d "
                            + "(%d observations). Je l'ajuste ?",
                    p.label.isEmpty() ? "ce rythme" : p.label, newH, newM, near.size());
            JSONObject proposal;
            try {
                proposal = new JSONObject()
                        .put("start", String.format(Locale.US, "%02d:%02d", newH, newM))
                        .put("startHour", newH)
                        .put("startMinute", newM);
            } catch (Exception e) {
                continue;
            }
            LearningCandidate c = new LearningCandidate(
                    "",
                    LearningCandidate.TYPE_ROUTINE_TIME_SHIFT,
                    p.id,
                    proposal,
                    near.size(),
                    conf,
                    reason,
                    LearningCandidate.STATUS_PENDING,
                    System.currentTimeMillis(),
                    System.currentTimeMillis(),
                    0L);
            if (best == null || c.confidence > best.confidence) best = c;
        }
        return best;
    }

    public static boolean minutesNear(int observed, int declaredStart, int window) {
        int d = Math.abs(observed - declaredStart);
        d = Math.min(d, 24 * 60 - d);
        return d <= window;
    }

    public static int median(List<Integer> values) {
        List<Integer> copy = new ArrayList<>(values);
        Collections.sort(copy);
        int n = copy.size();
        if (n % 2 == 1) return copy.get(n / 2);
        return (copy.get(n / 2 - 1) + copy.get(n / 2)) / 2;
    }
}
