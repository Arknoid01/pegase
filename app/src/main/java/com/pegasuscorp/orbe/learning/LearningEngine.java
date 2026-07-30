package com.pegasuscorp.orbe.learning;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.pegasuscorp.orbe.intentions.CarBluetooth;
import com.pegasuscorp.orbe.intentions.IntentionPrefs;
import com.pegasuscorp.orbe.intentions.rules.WorkWifiRule;
import com.pegasuscorp.orbe.learning.detectors.IntentionPreferenceDetector;
import com.pegasuscorp.orbe.learning.detectors.PatternDetector;
import com.pegasuscorp.orbe.learning.detectors.RoutineTimeDetector;
import com.pegasuscorp.orbe.life.LifePatternStore;

import org.json.JSONObject;

import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * Façade learning : enregistrer des signaux + lancer les détecteurs (rare).
 */
public final class LearningEngine {

    private static final String TAG = "LearningEngine";

    private static final List<PatternDetector> DETECTORS = Arrays.asList(
            new IntentionPreferenceDetector(),
            new RoutineTimeDetector()
    );

    private LearningEngine() {}

    public static void recordIntentionFeedback(Context ctx, String intentionId, String action) {
        if (ctx == null || TextUtils.isEmpty(intentionId) || TextUtils.isEmpty(action)) return;
        try {
            JSONObject payload = new JSONObject()
                    .put("intentionId", intentionId)
                    .put("action", action);
            ObservationStore.getInstance(ctx).append(new Observation(
                    Observation.TYPE_INTENTION_FEEDBACK,
                    System.currentTimeMillis(),
                    payload));
        } catch (Exception e) {
            Log.w(TAG, "recordIntentionFeedback", e);
        }
    }

    public static void recordBtAcl(Context ctx, BluetoothDevice device, boolean connected) {
        if (ctx == null || device == null || !connected) return;
        try {
            String name = "";
            try {
                name = device.getName();
            } catch (SecurityException ignored) {}
            if (TextUtils.isEmpty(name)) name = "";
            String fold = name.toLowerCase(Locale.ROOT);
            boolean interesting = CarBluetooth.isCarDevice(ctx, device)
                    || looksLikeAudioHeadset(fold);
            if (!interesting) return;

            JSONObject payload = new JSONObject()
                    .put("connected", true)
                    .put("btNameFold", fold.length() > 40 ? fold.substring(0, 40) : fold)
                    .put("duringPatternId", resolveDuringPatternId(ctx));
            ObservationStore.getInstance(ctx).append(new Observation(
                    Observation.TYPE_BT_ACL,
                    System.currentTimeMillis(),
                    payload));
        } catch (Exception e) {
            Log.w(TAG, "recordBtAcl", e);
        }
    }

    /**
     * Wi‑Fi connu seulement (SSID travail déclaré). Ignore unknown / SSID inconnus.
     */
    public static void recordWifi(Context ctx, String ssid, String previousSsid) {
        if (ctx == null) return;
        try {
            String now = WorkWifiRule.normalize(ssid);
            if (now.isEmpty() || WorkWifiRule.isUnknown(now)) return;
            String work = WorkWifiRule.normalize(IntentionPrefs.getWorkWifiSsid(ctx));
            if (work.isEmpty()) return;
            if (!work.equalsIgnoreCase(now)) return;
            String prev = WorkWifiRule.normalize(previousSsid);
            // Edge : entrée sur le Wi‑Fi travail (évite spam à chaque tick)
            if (work.equalsIgnoreCase(prev)) return;

            String fold = now.toLowerCase(Locale.ROOT);
            if (fold.length() > 40) fold = fold.substring(0, 40);
            JSONObject payload = new JSONObject()
                    .put("label", "work")
                    .put("ssidFold", fold)
                    .put("duringPatternId", resolveDuringPatternId(ctx));
            ObservationStore.getInstance(ctx).append(new Observation(
                    Observation.TYPE_WIFI_SSID,
                    System.currentTimeMillis(),
                    payload));
        } catch (Exception e) {
            Log.w(TAG, "recordWifi", e);
        }
    }

    /** Appelé après evaluateSensors — cooldown LearningPolicy. */
    public static void maybeRunDetectors(Context ctx) {
        if (ctx == null || !LearningPolicy.canRunDetectors(ctx)) return;
        runDetectorsInternal(ctx.getApplicationContext(), true);
    }

    /** Routines / debug : ignore le cooldown détecteurs une fois. */
    public static void runDetectorsNow(Context ctx) {
        if (ctx == null || !LearningPolicy.isEnabled(ctx)) return;
        runDetectorsInternal(ctx.getApplicationContext(), true);
    }

    private static void runDetectorsInternal(Context app, boolean markCooldown) {
        try {
            long since = System.currentTimeMillis() - ObservationStore.RETENTION_MS;
            List<Observation> all = ObservationStore.getInstance(app).listSince(since);
            LearningCandidateStore store = LearningCandidateStore.getInstance(app);
            LearningCandidate best = null;
            for (PatternDetector d : DETECTORS) {
                LearningCandidate c = d.detect(app, all);
                if (c == null) continue;
                LearningCandidate saved = store.upsertPending(c);
                if (saved != null && saved.isPending()) {
                    if (best == null || saved.confidence > best.confidence) best = saved;
                }
            }
            if (markCooldown) LearningPolicy.markDetectorsRan(app);
            if (best != null && LearningPolicy.canNotify(app)) {
                LearningNotifier.show(app, best);
                LearningPolicy.markNotified(app);
            }
        } catch (Exception e) {
            Log.w(TAG, "runDetectors", e);
        }
    }

    static String resolveDuringPatternId(Context ctx) {
        Calendar cal = Calendar.getInstance();
        for (LifePatternStore.LifePattern p : LifePatternStore.getInstance(ctx).listAll()) {
            if (p.isActiveNow(cal)) return p.id;
        }
        int nowM = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
        for (LifePatternStore.LifePattern p : LifePatternStore.getInstance(ctx).listAll()) {
            if (!p.active) continue;
            int start = p.startHour * 60 + p.startMinute;
            if (RoutineTimeDetector.minutesNear(nowM, start, RoutineTimeDetector.NEAR_WINDOW_MIN)) {
                return p.id;
            }
        }
        return "";
    }

    static boolean looksLikeAudioHeadset(String fold) {
        if (TextUtils.isEmpty(fold)) return false;
        return fold.contains("airpod")
                || fold.contains("earbud")
                || fold.contains("earbuds")
                || fold.contains("headset")
                || fold.contains("headphone")
                || fold.contains("écouteur")
                || fold.contains("ecouteur")
                || fold.contains("buds")
                || fold.contains("galaxy buds")
                || fold.contains("wh-")
                || fold.contains("sony") && fold.contains("wf");
    }
}
