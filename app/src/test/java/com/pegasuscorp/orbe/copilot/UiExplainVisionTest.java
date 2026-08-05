package com.pegasuscorp.orbe.copilot;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class UiExplainVisionTest {

    @Test
    public void needsVisionFallback_whenNoTextButBounds() {
        A11yUiMatcher.Target t = new A11yUiMatcher.Target(
                "", "icon_play", "android.widget.ImageView", true, 10, 20, 80, 90);
        assertTrue(UiExplainVision.needsVisionFallback(t));
    }

    @Test
    public void needsVisionFallback_falseWhenTextPresent() {
        A11yUiMatcher.Target t = new A11yUiMatcher.Target(
                "Lecture", "", "", true, 10, 20, 80, 90);
        assertFalse(UiExplainVision.needsVisionFallback(t));
    }

    @Test
    public void buildVisionPrompt_includesQuestion() {
        // Le libellé visible est le 1ᵉʳ argument ; le viewId (2ᵉ) n'entre jamais dans le
        // prompt — la règle du copilote est de ne raisonner que sur ce qui est affiché.
        A11yUiMatcher.Target t = new A11yUiMatcher.Target(
                "game_icon", "com.example:id/game_icon", "", false, 0, 0, 10, 10);
        String p = UiExplainVision.buildVisionPrompt("c'est quoi ce symbole", t);
        assertTrue(p.contains("c'est quoi ce symbole"));
        assertTrue(p.contains("game_icon"));
        assertFalse(p.contains("com.example:id/"));
    }
}
