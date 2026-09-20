package com.pegasuscorp.orbe.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class HomeLoadPolicyTest {

    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        HomeLoadPolicy.setLazyBootstrapEnabled(context, true);
    }

    @Test
    public void skipPrefetchOnHomeResume_whenLazyEnabled() {
        assertTrue(HomeLoadPolicy.skipPrefetchOnHomeResume(context));
    }

    @Test
    public void prefetchOnHomeResume_whenLazyDisabled() {
        HomeLoadPolicy.setLazyBootstrapEnabled(context, false);
        assertEquals(false, HomeLoadPolicy.skipPrefetchOnHomeResume(context));
    }

    @Test
    public void wakeOnHomeStableOnly_whenLazyEnabled() {
        assertTrue(HomeLoadPolicy.wakeOnHomeStableOnly(context));
    }

    @Test
    public void wakeNotStableOnly_whenLazyDisabled() {
        HomeLoadPolicy.setLazyBootstrapEnabled(context, false);
        assertEquals(false, HomeLoadPolicy.wakeOnHomeStableOnly(context));
    }

    @Test
    public void idlePrefetchJob_whenLazyEnabled() {
        assertTrue(HomeLoadPolicy.useIdlePrefetchJob(context));
    }

    @Test
    public void deferLocationAtBoot_whenLazyEnabled() {
        assertTrue(HomeLoadPolicy.deferLocationAtBoot(context));
    }

    @Test
    public void polishFlagsOff_whenLazyDisabled() {
        HomeLoadPolicy.setLazyBootstrapEnabled(context, false);
        assertFalse(HomeLoadPolicy.useIdlePrefetchJob(context));
        assertFalse(HomeLoadPolicy.deferLocationAtBoot(context));
    }
}
