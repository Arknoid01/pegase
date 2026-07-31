package com.pegasuscorp.orbe.copilot;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class CopilotLocaleFilterTest {

    @Test
    public void needsTranslation_english() {
        assertTrue(CopilotLocaleFilter.needsTranslation("Submit your application now"));
    }

    @Test
    public void needsTranslation_french() {
        assertFalse(CopilotLocaleFilter.needsTranslation(
                "Voici le résumé de la journée"));
    }

    @Test
    public void needsTranslation_frenchChromeWithoutAccents() {
        assertFalse(CopilotLocaleFilter.needsTranslation("Ouvrir la page d'accueil"));
        assertFalse(CopilotLocaleFilter.needsTranslation("Nouvel onglet"));
        assertFalse(CopilotLocaleFilter.needsTranslation("La connexion est sécurisée"));
    }

    @Test
    public void isBrowserChromeLabel_chromeToolbar() {
        assertTrue(CopilotLocaleFilter.isBrowserChromeLabel("Ouvrir la page d'accueil"));
        assertTrue(CopilotLocaleFilter.isBrowserChromeLabel("fr.wikipedia.org/wiki/Pegasus"));
    }

    @Test
    public void foreignBlocks_filtersSmall() {
        A11ySnapshot.Node big = new A11ySnapshot.Node(
                "Submit your application now please", 10, 400, 200, 480);
        A11ySnapshot.Node tiny = new A11ySnapshot.Node("Hi", 0, 0, 5, 5);
        A11ySnapshot.Node chrome = new A11ySnapshot.Node(
                "Ouvrir la page d'accueil", 0, 100, 200, 160);
        List<A11ySnapshot.Node> out = CopilotLocaleFilter.foreignBlocks(
                Arrays.asList(big, tiny, chrome));
        assertEquals(1, out.size());
        assertEquals("Submit your application now please", out.get(0).text);
    }
}
