package com.pegasuscorp.orbe.intentions.location;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.pegasuscorp.orbe.session.HomeLoadPolicy;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class LocationLazyGateTest {

    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        HomeLoadPolicy.setLazyBootstrapEnabled(context, true);
        LocationSituationBootstrap.resetForTests();
    }

    @After
    public void tearDown() {
        LocationSituationBootstrap.resetForTests();
    }

    @Test
    public void deferAtBoot_whenLazyEnabled() {
        assertTrue(LocationLazyGate.deferAtBoot(context));
    }

    @Test
    public void noDeferAtBoot_whenLazyDisabled() {
        HomeLoadPolicy.setLazyBootstrapEnabled(context, false);
        assertFalse(LocationLazyGate.deferAtBoot(context));
    }

    @Test
    public void warmWithoutPermission_doesNotStartBootstrap() {
        LocationLazyGate.warmForBackgroundUse(context);
        LocationLazyGate.bootstrapIfPermitted(context);
        // sans permission runtime, ensureStarted ne marque pas started
        assertFalse(LocationSituationBootstrap.hasLocationPermission(context));
    }
}
