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
        // Heuristique : >=2 mots FR + accents → pas de traduction
        assertFalse(CopilotLocaleFilter.needsTranslation(
                "Voici le résumé de la journée"));
    }

    @Test
    public void foreignBlocks_filtersSmall() {
        A11ySnapshot.Node big = new A11ySnapshot.Node(
                "Hello world test", 10, 10, 200, 40);
        A11ySnapshot.Node tiny = new A11ySnapshot.Node("Hi", 0, 0, 5, 5);
        List<A11ySnapshot.Node> out = CopilotLocaleFilter.foreignBlocks(
                Arrays.asList(big, tiny));
        assertEquals(1, out.size());
        assertEquals("Hello world test", out.get(0).text);
    }
}
