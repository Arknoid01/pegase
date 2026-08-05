package com.pegasuscorp.orbe.copilot;

import android.graphics.Rect;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class A11yRematchTest {

    @Test
    public void stillMatches_okWhenCentered() {
        A11yUiMatcher.Target preview = new A11yUiMatcher.Target(
                "Envoyer", "", "Button", true, 100, 200, 300, 280);
        Rect live = new Rect(110, 210, 310, 290);
        assertTrue(A11yClickRematch.stillMatches(preview, "Envoyer", null, live));
    }

    @Test
    public void stillMatches_rejectsLargeDrift() {
        A11yUiMatcher.Target preview = new A11yUiMatcher.Target(
                "Envoyer", "", "Button", true, 100, 200, 300, 280);
        Rect live = new Rect(500, 800, 700, 880);
        assertFalse(A11yClickRematch.stillMatches(preview, "Envoyer", null, live));
    }

    @Test
    public void stillMatches_rejectsLabelChange() {
        A11yUiMatcher.Target preview = new A11yUiMatcher.Target(
                "Envoyer", "", "Button", true, 100, 200, 300, 280);
        Rect live = new Rect(100, 200, 300, 280);
        assertFalse(A11yClickRematch.stillMatches(preview, "Annuler", null, live));
    }
}
