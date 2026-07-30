package com.pegasuscorp.orbe.learning;

import android.content.Context;

import com.pegasuscorp.orbe.intentions.IntentionIds;
import com.pegasuscorp.orbe.intentions.IntentionPrefs;
import com.pegasuscorp.orbe.life.LifePatternStore;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.Calendar;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class SituationPromptBuilderTest {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = RuntimeEnvironment.getApplication();
        LifePatternStore.resetInstanceForTests();
        LearningCandidateStore.resetInstanceForTests();
        IntentionPrefs.clearAll(ctx);
        LifePatternStore.getInstance(ctx).clearAll();
        LearningCandidateStore.getInstance(ctx).clearAll();
    }

    @Test
    public void activeRhythm_appears() {
        LifePatternStore.getInstance(ctx).add("Ménage", "", 18, 30, 19, 45);
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 18);
        cal.set(Calendar.MINUTE, 40);
        cal.set(Calendar.SECOND, 0);
        String block = SituationPromptBuilder.promptBlock(ctx, cal.getTimeInMillis(), null);
        assertTrue(block.contains("Situation maintenant"));
        assertTrue(block.contains("Rythme en cours"));
        assertTrue(block.contains("Ménage"));
        assertFalse(block.contains("Bientôt"));
    }

    @Test
    public void soonRhythm_appearsOutsideActive() {
        LifePatternStore.getInstance(ctx).add("Sport", "", 18, 30, 19, 0);
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 18);
        cal.set(Calendar.MINUTE, 20);
        cal.set(Calendar.SECOND, 0);
        String block = SituationPromptBuilder.promptBlock(ctx, cal.getTimeInMillis(), null);
        assertTrue(block.contains("Bientôt"));
        assertTrue(block.contains("Sport"));
        assertFalse(block.contains("Rythme en cours"));
    }

    @Test
    public void workWifi_onlyWhenKnownMatch() {
        IntentionPrefs.setWorkWifiSsid(ctx, "Boucherie-Wifi");
        String silent = SituationPromptBuilder.promptBlock(
                ctx, System.currentTimeMillis(), "Autre-Reseau");
        assertFalse(silent.contains("Wi‑Fi travail") || silent.contains("Wi-Fi travail"));

        String work = SituationPromptBuilder.promptBlock(
                ctx, System.currentTimeMillis(), "Boucherie-Wifi");
        assertTrue(work.contains("Wi‑Fi travail") || work.contains("Wi-Fi travail"));
    }

    @Test
    public void acceptedSuppress_inSituation_pendingNot() throws Exception {
        LearningCandidateStore store = LearningCandidateStore.getInstance(ctx);
        LearningCandidate pending = store.upsertPending(new LearningCandidate(
                "",
                LearningCandidate.TYPE_INTENTION_SUPPRESS,
                IntentionIds.BATTERY_LOW,
                new JSONObject().put("suppress", true),
                3, 0.8, "pending reason",
                LearningCandidate.STATUS_PENDING,
                System.currentTimeMillis(),
                System.currentTimeMillis(),
                0L));
        assertNotNull(pending);

        String withPending = SituationPromptBuilder.promptBlock(ctx);
        assertFalse(withPending.contains("Préférences apprises"));
        assertFalse(withPending.contains("Batterie faible"));

        store.replace(pending.id, pending.withStatus(
                LearningCandidate.STATUS_ACCEPTED, System.currentTimeMillis()));
        String withAccepted = SituationPromptBuilder.promptBlock(ctx);
        assertTrue(withAccepted.contains("Préférences apprises"));
        assertTrue(withAccepted.contains("Batterie faible"));
    }

    @Test
    public void emptyWhenNothingRelevant() {
        String block = SituationPromptBuilder.promptBlock(
                ctx, System.currentTimeMillis(), "Random");
        assertEquals("", block);
    }
}
