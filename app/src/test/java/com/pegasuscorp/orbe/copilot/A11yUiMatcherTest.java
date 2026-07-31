package com.pegasuscorp.orbe.copilot;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class A11yUiMatcherTest {

    @Test
    public void matchesFields_byText() {
        A11yUiMatcher.Criteria c = A11yUiMatcher.Criteria.fromText("Sous-titres");
        assertTrue(A11yUiMatcher.matchesFields("Activer les sous-titres", "", "", c));
    }

    @Test
    public void matchesFields_byViewId() {
        A11yUiMatcher.Criteria c = A11yUiMatcher.Criteria.fromViewId("caption_button");
        assertTrue(A11yUiMatcher.matchesFields("", "com.youtube:id/caption_button", "", c));
    }

    @Test
    public void matchesFields_requiresBothWhenSet() {
        A11yUiMatcher.Criteria c = new A11yUiMatcher.Criteria();
        c.text = "ok";
        c.viewId = "missing";
        assertFalse(A11yUiMatcher.matchesFields("ok", "other_id", "", c));
    }

    @Test
    public void criteriaEmpty() {
        assertTrue(new A11yUiMatcher.Criteria().isEmpty());
        assertFalse(A11yUiMatcher.Criteria.fromText("x").isEmpty());
    }
}
