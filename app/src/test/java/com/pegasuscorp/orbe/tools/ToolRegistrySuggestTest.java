package com.pegasuscorp.orbe.tools;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ToolRegistrySuggestTest {

    @Test
    public void suggestSimilarIds_findsCloseNames() {
        ToolRegistry reg = new ToolRegistry();
        List<String> s = reg.suggestSimilarIds("ui_acton");
        assertFalse(s.isEmpty());
        assertTrue(s.contains("ui_action") || s.toString().contains("ui_"));
    }

    @Test
    public void unknownToolMessage_includesSuggestions() {
        String msg = ToolRegistry.unknownToolMessage("ui_acton",
                java.util.Arrays.asList("ui_action", "ui_loop"));
        assertTrue(msg.contains("ui_action"));
        assertTrue(msg.contains("veux-tu dire"));
    }
}
