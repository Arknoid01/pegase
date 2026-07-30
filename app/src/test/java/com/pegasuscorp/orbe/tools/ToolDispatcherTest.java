package com.pegasuscorp.orbe.tools;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class ToolDispatcherTest {

    @Test
    public void extractJson_pureToolCall() {
        String json = ToolDispatcher.extractJson("{\"tool\":\"weather\",\"params\":{\"days\":1}}");
        assertNotNull(json);
        assertTrue(json.contains("\"weather\""));
    }

    @Test
    public void extractJson_withProseBefore() {
        String raw = "D'accord, voici : {\"tool\":\"nasa\",\"params\":{}}";
        String json = ToolDispatcher.extractJson(raw);
        assertNotNull(json);
        assertEquals("{\"tool\":\"nasa\",\"params\":{}}", json);
    }

    @Test
    public void extractJson_markdownFence() {
        String raw = "```json\n{\"tool\":\"timer\",\"params\":{\"seconds\":300}}\n```";
        String json = ToolDispatcher.extractJson(raw);
        assertNotNull(json);
        assertTrue(json.contains("timer"));
    }

    @Test
    public void extractJson_rejectsInvalidTool() {
        assertNull(ToolDispatcher.extractJson("{\"params\":{\"days\":1}}"));
        assertNull(ToolDispatcher.extractJson("{\"tool\":\"\",\"params\":{}}"));
    }

    @Test
    public void looksLikeToolAttempt_detectsBrokenJson() {
        assertTrue(ToolDispatcher.looksLikeToolAttempt("Voici {\"tool\":\"weather\""));
        assertFalse(ToolDispatcher.looksLikeToolAttempt("{\"tool\":\"weather\",\"params\":{}}"));
    }

    @Test
    public void stripToolCall_keepsProse() {
        String raw = "Je regarde. {\"tool\":\"nasa\",\"params\":{}}";
        assertEquals("Je regarde.", ToolDispatcher.stripToolCall(raw));
    }

    @Test
    public void cleanForDisplay_stripsJsonAndFixesSpacing() {
        String out = ToolDispatcher.cleanForDisplay("Ok {\"tool\":\"nasa\",\"params\":{}}");
        assertFalse(out.contains("{"));
        assertEquals("Ok", out);
    }

    @Test
    public void findMatchingBrace_nestedParams() {
        String s = "{\"tool\":\"x\",\"params\":{\"q\":\"a{b}c\"}}";
        int end = ToolDispatcher.findMatchingBrace(s, 0);
        assertEquals(s.length() - 1, end);
    }
}
