package com.pegasuscorp.orbe.copilot;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class UiExplainHelperTest {

    private static final String PARAGRAPH =
            "La sérendipité désigne une découverte inattendue. Elle survient souvent "
                    + "par hasard pendant une recherche. Le terme vient d'un conte persan.";

    @Test
    public void focusedExcerpt_returnsSentenceAroundWord() {
        String out = UiExplainHelper.focusedExcerpt(PARAGRAPH, "hasard");
        assertTrue(out.contains("hasard"));
        assertTrue(out.length() < PARAGRAPH.length());
        assertFalse(out.contains("sérendipité"));
    }

    @Test
    public void focusedExcerpt_wordAbsent_returnsEmpty() {
        assertEquals("", UiExplainHelper.focusedExcerpt(PARAGRAPH, "absent"));
    }

    @Test
    public void focusedExcerpt_caseInsensitive() {
        String out = UiExplainHelper.focusedExcerpt(PARAGRAPH, "SÉRENDIPITÉ");
        assertTrue(out.contains("sérendipité"));
    }

    @Test
    public void localAnswer_longParagraph_focusesRequestedWord() {
        A11yUiMatcher.Target target = new A11yUiMatcher.Target(
                PARAGRAPH, "", "android.widget.TextView", false, 0, 0, 400, 200);
        String answer = UiExplainHelper.localAnswer(target, "hasard", "c'est quoi ?");
        assertTrue(answer.contains("hasard"));
        assertTrue(answer.length() < PARAGRAPH.length());
    }

    @Test
    public void localAnswer_shortLabel_returnsLabel() {
        A11yUiMatcher.Target target = new A11yUiMatcher.Target(
                "Rechercher", "", "android.widget.Button", true, 0, 0, 100, 40);
        assertEquals("Rechercher",
                UiExplainHelper.localAnswer(target, "Rechercher", ""));
    }

    @Test
    public void localAnswer_wordNotInParagraph_returnsFullText() {
        A11yUiMatcher.Target target = new A11yUiMatcher.Target(
                PARAGRAPH, "", "android.widget.TextView", false, 0, 0, 400, 200);
        assertEquals(PARAGRAPH,
                UiExplainHelper.localAnswer(target, "introuvable", ""));
    }
}
