package com.pegasuscorp.orbe.objects;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import com.pegasuscorp.orbe.intentions.IntentionCandidate;
import com.pegasuscorp.orbe.intentions.IntentionIds;
import com.pegasuscorp.orbe.intentions.IntentionPolicy;
import com.pegasuscorp.orbe.intentions.IntentionPrefs;
import com.pegasuscorp.orbe.intentions.ContextSnapshot;
import com.pegasuscorp.orbe.intentions.rules.ProjectObjectRule;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class ProjectObjectStoreTest {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = RuntimeEnvironment.getApplication();
        ProjectObjectStore.resetInstanceForTests();
        IntentionPrefs.clearAll(ctx);
        IntentionPrefs.setEnabled(ctx, true);
        IntentionPrefs.setQuietHours(ctx, 22, 7);
    }

    @Test
    public void failure_promptsAndIntention() {
        ProjectObjectStore store = ProjectObjectStore.getInstance(ctx);
        store.putOrionFailureForTests("Volume incompatible");
        String block = store.promptBlock();
        assertTrue(block.contains("Orion"));
        assertTrue(block.contains("Volume incompatible"));
        assertTrue(store.orionHasRecentFailure(7L * 24 * 60 * 60 * 1000));

        ProjectObjectRule rule = new ProjectObjectRule(ctx);
        IntentionCandidate c = rule.evaluate(new ContextSnapshot(
                80, false, "", 80, "", "", false, false, false, System.currentTimeMillis()));
        assertNotNull(c);
        assertEquals(IntentionIds.ORION_RETRY, c.id);
        assertTrue(IntentionPolicy.canFire(ctx, c));
    }

    @Test
    public void customFiche_crudAndPrompt() {
        ProjectObjectStore store = ProjectObjectStore.getInstance(ctx);
        String id = store.upsertCustom(null, "Cuisine", "plan îlot", "en cours");
        assertNotNull(id);
        assertEquals(1, store.listCustom().size());
        String block = store.promptBlock();
        assertTrue(block.contains("Cuisine"));
        assertTrue(block.contains("plan îlot"));
        assertTrue(store.remove(id));
        assertTrue(store.listCustom().isEmpty());
    }

    @Test
    public void carName_match() {
        assertTrue(com.pegasuscorp.orbe.intentions.CarBluetooth.isCarName(
                "BMW 320d", ""));
        assertTrue(com.pegasuscorp.orbe.intentions.CarBluetooth.isCarName(
                "MyCarKit", "mycar"));
        assertFalse(com.pegasuscorp.orbe.intentions.CarBluetooth.isCarName(
                "AirPods Pro", ""));
    }
}
