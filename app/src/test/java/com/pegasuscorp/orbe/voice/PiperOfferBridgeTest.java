package com.pegasuscorp.orbe.voice;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.pegasuscorp.orbe.session.HomeLoadPolicy;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class PiperOfferBridgeTest {

    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        HomeLoadPolicy.setLazyBootstrapEnabled(context, true);
    }

    @Test
    public void shouldNotGate_whenLazyDisabled() {
        HomeLoadPolicy.setLazyBootstrapEnabled(context, false);
        assertFalse(PiperOfferBridge.shouldGate(context));
    }

    @Test
    public void shouldNotGate_whenPiperDisabled() {
        PiperModelStore.setUsePiper(context, false);
        assertFalse(PiperOfferBridge.shouldGate(context));
    }
}
