package com.pegasuscorp.orbe.tools;

import org.junit.Test;

import static org.junit.Assert.*;

public class ToolSignatureTest {

    @Test
    public void optionalMarker_isRead() {
        ToolSignature sig = ToolSignature.parse("alarm(action:\"add\"|\"list\", hour?:int) — Réveil");
        assertEquals(2, sig.params.size());
        assertFalse(sig.find("action").optional);
        assertTrue(sig.find("hour").optional);
        assertEquals("integer", sig.find("hour").type);
        assertEquals("Réveil", sig.summary);
    }

    @Test
    public void quotedAndBareEnums_areExtracted() {
        ToolSignature quoted = ToolSignature.parse("flashlight(action:\"on\"|\"off\"|\"toggle\") — x");
        assertEquals(3, quoted.find("action").enumValues.size());
        ToolSignature bare = ToolSignature.parse("f1(action:refresh|status|debrief) — x");
        assertEquals(3, bare.find("action").enumValues.size());
        assertEquals("string", bare.find("action").type);
    }

    @Test
    public void typeAlternation_isNotAnEnum() {
        ToolSignature sig = ToolSignature.parse("git_commit(changes?:str|list, branch?:str) — x");
        assertTrue(sig.find("changes").enumValues.isEmpty());
        assertEquals("string", sig.find("changes").type);
    }

    @Test
    public void defaultValue_isNotAnEnum_andMakesParamOptional() {
        ToolSignature sig = ToolSignature.parse("wikipedia(query:str, lang?:str=\"fr\") — x");
        assertTrue(sig.find("lang").enumValues.isEmpty());
        assertTrue(sig.find("lang").optional);
    }

    @Test
    public void nestedArray_doesNotSplitOnInnerComma() {
        ToolSignature sig = ToolSignature.parse(
                "composite(label?:str, steps:[{tool,params}]) — Séquence");
        assertEquals(2, sig.params.size());
        assertEquals("array", sig.find("steps").type);
    }

    @Test
    public void ellipsis_andNoParams_yieldNoParams() {
        assertEquals(0, ToolSignature.parse("calendar(...) — alias").params.size());
        assertEquals(0, ToolSignature.parse("nasa() — image").params.size());
        assertEquals(0, ToolSignature.parse("pas une signature").params.size());
    }

    @Test
    public void parenthesesInsideSummary_areKept() {
        ToolSignature sig = ToolSignature.parse("x(a:str) — Fait (vraiment) ça");
        assertEquals("Fait (vraiment) ça", sig.summary);
        assertEquals(1, sig.params.size());
    }
}
