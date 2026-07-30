package com.pegasuscorp.orbe.learning;

import android.content.Context;

import com.pegasuscorp.orbe.intentions.ContextSnapshot;
import com.pegasuscorp.orbe.intentions.IntentionCandidate;
import com.pegasuscorp.orbe.intentions.IntentionIds;
import com.pegasuscorp.orbe.intentions.IntentionPrefs;
import com.pegasuscorp.orbe.intentions.rules.LifePatternSoonRule;
import com.pegasuscorp.orbe.learning.detectors.IntentionPreferenceDetector;
import com.pegasuscorp.orbe.learning.detectors.RoutineTimeDetector;
import com.pegasuscorp.orbe.life.LifePatternStore;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class LearningPipelineTest {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = RuntimeEnvironment.getApplication();
        ObservationStore.resetInstanceForTests();
        LearningCandidateStore.resetInstanceForTests();
        LifePatternStore.resetInstanceForTests();
        LearningPolicy.clearAll(ctx);
        IntentionPrefs.clearAll(ctx);
        ObservationStore.getInstance(ctx).clearAll();
        LearningCandidateStore.getInstance(ctx).clearAll();
    }

    @Test
    public void observationStore_roundTrip() throws Exception {
        ObservationStore store = ObservationStore.getInstance(ctx);
        JSONObject payload = new JSONObject()
                .put("intentionId", IntentionIds.BATTERY_LOW)
                .put("action", IntentionIds.ACTION_NEVER);
        long at = System.currentTimeMillis();
        store.append(new Observation(Observation.TYPE_INTENTION_FEEDBACK, at, payload));
        List<Observation> list = store.listSince(at - 1000);
        assertEquals(1, list.size());
        assertEquals(Observation.TYPE_INTENTION_FEEDBACK, list.get(0).type);
        assertEquals(IntentionIds.BATTERY_LOW, list.get(0).payload.optString("intentionId"));
    }

    @Test
    public void candidateStore_roundTripAndPrompt() throws Exception {
        LearningCandidateStore store = LearningCandidateStore.getInstance(ctx);
        JSONObject proposal = new JSONObject().put("suppress", true);
        LearningCandidate c = new LearningCandidate(
                "c1",
                LearningCandidate.TYPE_INTENTION_SUPPRESS,
                IntentionIds.BATTERY_LOW,
                proposal,
                3,
                0.7,
                "Tu as écarté batterie 3 fois.",
                LearningCandidate.STATUS_PENDING,
                System.currentTimeMillis(),
                System.currentTimeMillis(),
                0L);
        assertNotNull(store.upsertPending(c));
        assertEquals(1, store.listPending().size());
        String block = store.promptBlock();
        assertTrue(block.contains("Apprentissages en attente"));
        assertTrue(block.contains("Suggestion trop fréquente"));
    }

    @Test
    public void intentionPreferenceDetector_needsThreeNegatives() throws Exception {
        IntentionPreferenceDetector det = new IntentionPreferenceDetector();
        List<Observation> few = feedbackObs(IntentionIds.BATTERY_LOW,
                IntentionIds.ACTION_NEVER, 2);
        assertNull(det.detect(ctx, few));

        List<Observation> enough = feedbackObs(IntentionIds.BATTERY_LOW,
                IntentionIds.ACTION_IGNORE_TODAY, 3);
        LearningCandidate c = det.detect(ctx, enough);
        assertNotNull(c);
        assertEquals(LearningCandidate.TYPE_INTENTION_SUPPRESS, c.type);
        assertEquals(IntentionIds.BATTERY_LOW, c.targetId);
        assertEquals(3, c.evidenceCount);
    }

    @Test
    public void intentionPreferenceDetector_skipsAlreadySuppressed() throws Exception {
        IntentionPrefs.suppress(ctx, IntentionIds.WORK_WIFI);
        IntentionPreferenceDetector det = new IntentionPreferenceDetector();
        LearningCandidate c = det.detect(ctx, feedbackObs(
                IntentionIds.WORK_WIFI, IntentionIds.ACTION_NEVER, 5));
        assertNull(c);
    }

    @Test
    public void routineTimeDetector_medianAndShift() {
        assertEquals(18 * 60 + 40, RoutineTimeDetector.median(
                Arrays.asList(18 * 60 + 30, 18 * 60 + 40, 18 * 60 + 50)));
        assertTrue(RoutineTimeDetector.minutesNear(18 * 60 + 45, 18 * 60 + 30, 45));
        assertFalse(RoutineTimeDetector.minutesNear(20 * 60, 18 * 60 + 30, 45));
    }

    @Test
    public void routineTimeDetector_proposesShift() throws Exception {
        LifePatternStore life = LifePatternStore.getInstance(ctx);
        LifePatternStore.LifePattern p = life.add("Ménage", "", 18, 30, 19, 45);
        assertNotNull(p);

        List<Observation> obs = new ArrayList<>();
        // médiane ~18:50 → décalage ≥ 10 min
        int[] starts = {18 * 60 + 48, 18 * 60 + 50, 18 * 60 + 52, 18 * 60 + 49, 18 * 60 + 51};
        long base = System.currentTimeMillis();
        for (int i = 0; i < starts.length; i++) {
            Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(base - i * 24L * 60L * 60L * 1000L);
            cal.set(Calendar.HOUR_OF_DAY, starts[i] / 60);
            cal.set(Calendar.MINUTE, starts[i] % 60);
            cal.set(Calendar.SECOND, 0);
            JSONObject payload = new JSONObject()
                    .put("connected", true)
                    .put("duringPatternId", p.id);
            obs.add(new Observation(Observation.TYPE_BT_ACL, cal.getTimeInMillis(), payload));
        }

        RoutineTimeDetector det = new RoutineTimeDetector();
        LearningCandidate c = det.detect(ctx, obs);
        assertNotNull(c);
        assertEquals(LearningCandidate.TYPE_ROUTINE_TIME_SHIFT, c.type);
        assertEquals(p.id, c.targetId);
        assertEquals(18, c.proposal.optInt("startHour"));
        assertTrue(c.proposal.optInt("startMinute") >= 48);
    }

    @Test
    public void policy_refuseCooldownBlocksSameFingerprint() throws Exception {
        LearningCandidateStore store = LearningCandidateStore.getInstance(ctx);
        JSONObject proposal = new JSONObject().put("suppress", true).put("intentionId", "x");
        LearningCandidate pending = new LearningCandidate(
                "",
                LearningCandidate.TYPE_INTENTION_SUPPRESS,
                IntentionIds.BATTERY_LOW,
                proposal,
                3,
                0.8,
                "raison",
                LearningCandidate.STATUS_PENDING,
                System.currentTimeMillis(),
                System.currentTimeMillis(),
                0L);
        LearningCandidate stored = store.upsertPending(pending);
        assertNotNull(stored);

        LearningFeedback.refuse(ctx, stored.id);
        LearningCandidate again = store.upsertPending(new LearningCandidate(
                "",
                LearningCandidate.TYPE_INTENTION_SUPPRESS,
                IntentionIds.BATTERY_LOW,
                proposal,
                4,
                0.85,
                "encore",
                LearningCandidate.STATUS_PENDING,
                System.currentTimeMillis(),
                System.currentTimeMillis(),
                0L));
        assertNull(again);
    }

    @Test
    public void accept_suppressAppliesIntentionPrefs() throws Exception {
        LearningCandidateStore store = LearningCandidateStore.getInstance(ctx);
        JSONObject proposal = new JSONObject().put("suppress", true);
        LearningCandidate stored = store.upsertPending(new LearningCandidate(
                "",
                LearningCandidate.TYPE_INTENTION_SUPPRESS,
                IntentionIds.BATTERY_LOW,
                proposal,
                3,
                0.8,
                "ok",
                LearningCandidate.STATUS_PENDING,
                System.currentTimeMillis(),
                System.currentTimeMillis(),
                0L));
        assertFalse(IntentionPrefs.isSuppressed(ctx, IntentionIds.BATTERY_LOW));
        LearningFeedback.accept(ctx, stored.id);
        assertTrue(IntentionPrefs.isSuppressed(ctx, IntentionIds.BATTERY_LOW));
        assertEquals(LearningCandidate.STATUS_ACCEPTED, store.get(stored.id).status);
    }

    @Test
    public void recordWifi_onlyKnownWorkSsid() {
        IntentionPrefs.setWorkWifiSsid(ctx, "Boucherie-Wifi");
        LearningEngine.recordWifi(ctx, "Autre-Reseau", "");
        assertEquals(0, ObservationStore.getInstance(ctx).countAll());

        LearningEngine.recordWifi(ctx, "<unknown ssid>", "");
        assertEquals(0, ObservationStore.getInstance(ctx).countAll());

        LearningEngine.recordWifi(ctx, "Boucherie-Wifi", "");
        assertEquals(1, ObservationStore.getInstance(ctx).countAll());
        Observation o = ObservationStore.getInstance(ctx).listSince(0).get(0);
        assertEquals(Observation.TYPE_WIFI_SSID, o.type);
        assertEquals("work", o.payload.optString("label"));

        // pas de spam si déjà sur le Wi‑Fi travail
        LearningEngine.recordWifi(ctx, "Boucherie-Wifi", "Boucherie-Wifi");
        assertEquals(1, ObservationStore.getInstance(ctx).countAll());
    }

    @Test
    public void lifePatternSoonRule_window() {
        LifePatternStore.LifePattern p = LifePatternStore.getInstance(ctx)
                .add("Ménage", "", 18, 30, 19, 45);
        assertNotNull(p);
        LifePatternSoonRule rule = new LifePatternSoonRule(ctx);

        Calendar soon = Calendar.getInstance();
        soon.set(Calendar.HOUR_OF_DAY, 18);
        soon.set(Calendar.MINUTE, 20);
        soon.set(Calendar.SECOND, 0);
        soon.set(Calendar.MILLISECOND, 0);
        IntentionCandidate c = rule.evaluate(snapAt(soon.getTimeInMillis()));
        assertNotNull(c);
        assertEquals(p.intentionId(), c.id);
        assertTrue(c.body.contains("Dans"));

        Calendar far = Calendar.getInstance();
        far.set(Calendar.HOUR_OF_DAY, 17);
        far.set(Calendar.MINUTE, 0);
        assertNull(rule.evaluate(snapAt(far.getTimeInMillis())));

        Calendar inside = Calendar.getInstance();
        inside.set(Calendar.HOUR_OF_DAY, 18);
        inside.set(Calendar.MINUTE, 40);
        assertNull(rule.evaluate(snapAt(inside.getTimeInMillis())));
    }

    @Test
    public void lifePatternSoonRule_boostWidensWindow() {
        LifePatternStore.LifePattern p = LifePatternStore.getInstance(ctx)
                .add("Sport", "", 18, 30, 19, 0);
        IntentionPrefs.setPreferEarlier(ctx, p.intentionId(), true);
        LifePatternSoonRule rule = new LifePatternSoonRule(ctx);

        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 18);
        cal.set(Calendar.MINUTE, 10); // 20 min avant — hors 15, dans 25
        cal.set(Calendar.SECOND, 0);
        assertNotNull(rule.evaluate(snapAt(cal.getTimeInMillis())));
    }

    @Test
    public void intentionPreferenceDetector_acceptBoost() throws Exception {
        IntentionPreferenceDetector det = new IntentionPreferenceDetector();
        assertNull(det.detect(ctx, feedbackObs(IntentionIds.DRIVE_BT,
                IntentionIds.ACTION_ACCEPT, 2)));

        LearningCandidate c = det.detect(ctx, feedbackObs(IntentionIds.DRIVE_BT,
                IntentionIds.ACTION_ACCEPT, 3));
        assertNotNull(c);
        assertEquals(LearningCandidate.TYPE_INTENTION_BOOST, c.type);
        assertEquals(IntentionIds.DRIVE_BT, c.targetId);

        LearningFeedback.accept(ctx, LearningCandidateStore.getInstance(ctx)
                .upsertPending(c).id);
        assertTrue(IntentionPrefs.prefersEarlier(ctx, IntentionIds.DRIVE_BT));
    }

    @Test
    public void runDetectorsNow_createsPendingFromNegatives() throws Exception {
        ObservationStore store = ObservationStore.getInstance(ctx);
        for (Observation o : feedbackObs(IntentionIds.BATTERY_LOW,
                IntentionIds.ACTION_NEVER, 3)) {
            store.append(o);
        }
        // Évite le chemin Notification (Robolectric) — on teste le PENDING.
        LearningPolicy.markNotified(ctx);
        LearningEngine.runDetectorsNow(ctx);
        List<LearningCandidate> pending = LearningCandidateStore.getInstance(ctx).listPending();
        assertFalse(pending.isEmpty());
        assertEquals(LearningCandidate.TYPE_INTENTION_SUPPRESS, pending.get(0).type);
    }

    private static ContextSnapshot snapAt(long nowMs) {
        return new ContextSnapshot(
                80, false, "", -1, "", "", false, false, false, nowMs);
    }

    private static List<Observation> feedbackObs(String intentionId, String action, int n)
            throws Exception {
        List<Observation> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (int i = 0; i < n; i++) {
            JSONObject payload = new JSONObject()
                    .put("intentionId", intentionId)
                    .put("action", action);
            out.add(new Observation(Observation.TYPE_INTENTION_FEEDBACK,
                    now - i * 60_000L, payload));
        }
        return out;
    }
}
