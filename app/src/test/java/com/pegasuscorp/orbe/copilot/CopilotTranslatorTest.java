package com.pegasuscorp.orbe.copilot;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class CopilotTranslatorTest {

    @Test
    public void parseResponse_numberedLines() {
        List<A11ySnapshot.Node> nodes = Arrays.asList(
                new A11ySnapshot.Node("Hello", 0, 0, 100, 30),
                new A11ySnapshot.Node("World", 0, 40, 100, 70));
        String raw = "1|Bonjour\n2|Monde";
        List<TranslationOverlayService.TranslatedBlock> blocks =
                CopilotTranslator.parseResponse(nodes, raw);
        assertEquals(2, blocks.size());
        assertEquals("Bonjour", blocks.get(0).translated);
        assertEquals("Monde", blocks.get(1).translated);
        assertEquals(0, blocks.get(0).top);
        assertEquals(40, blocks.get(1).top);
    }
}
