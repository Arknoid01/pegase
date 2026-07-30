package com.pegasuscorp.orbe.f1companion;

import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class F1DebriefOfferTest {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = RuntimeEnvironment.getApplication();
        F1DebriefOffer.resetForTests(ctx);
    }

    @After
    public void tearDown() {
        F1DebriefOffer.resetForTests(ctx);
    }

    @Test
    public void seedThenNewRace_setsPending() {
        F1DebriefOffer.seedAckForTests(ctx, 100);
        F1DebriefOffer.setPending(ctx, 200, "Grand Prix de Test");
        assertEquals(200, F1DebriefOffer.getPendingSessionKey(ctx));
        assertEquals("Grand Prix de Test", F1DebriefOffer.getPendingLabel(ctx));

        F1DebriefOffer.acknowledgePending(ctx);
        assertEquals(0, F1DebriefOffer.getPendingSessionKey(ctx));
    }
}
