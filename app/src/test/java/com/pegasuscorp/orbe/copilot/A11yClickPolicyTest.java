package com.pegasuscorp.orbe.copilot;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class A11yClickPolicyTest {

    @Test
    public void link_alwaysConfirm() {
        A11yUiMatcher.Target t = new A11yUiMatcher.Target(
                "https://example.com", "", "android.widget.TextView", true,
                0, 0, 10, 10);
        assertEquals(A11yClickPolicy.Level.ALWAYS, A11yClickPolicy.evaluate(t));
    }

    @Test
    public void denylist_conditional() {
        A11yUiMatcher.Target t = new A11yUiMatcher.Target(
                "Envoyer", "", "android.widget.Button", true, 0, 0, 10, 10);
        assertEquals(A11yClickPolicy.Level.CONDITIONAL, A11yClickPolicy.evaluate(t));
    }

    @Test
    public void safe_never() {
        A11yUiMatcher.Target t = new A11yUiMatcher.Target(
                "Paramètres", "", "android.widget.Button", true, 0, 0, 10, 10);
        assertEquals(A11yClickPolicy.Level.NEVER, A11yClickPolicy.evaluate(t));
    }

    @Test
    public void denylist_viewId() {
        A11yUiMatcher.Target t = new A11yUiMatcher.Target(
                "", "btn_delete_item", "", true, 0, 0, 10, 10);
        assertTrue(A11yClickPolicy.matchesDenylist(t));
    }
}
