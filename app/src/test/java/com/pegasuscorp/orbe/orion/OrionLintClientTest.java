package com.pegasuscorp.orbe.orion;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class OrionLintClientTest {

    @Test
    public void parse_normalizesIssues() throws Exception {
        JSONObject json = new JSONObject()
                .put("file", "timer.js")
                .put("tool", "eslint")
                .put("ok", false)
                .put("issues", new JSONArray()
                        .put(new JSONObject()
                                .put("line", 12)
                                .put("column", 5)
                                .put("severity", "error")
                                .put("rule", "no-undef")
                                .put("message", "x is not defined")));
        LintReport r = OrionLintClient.parse(json, "timer.js");
        assertNotNull(r);
        assertFalse(r.ok);
        assertFalse(r.toolMissing);
        assertEquals(1, r.errorCount());
        assertTrue(r.hasVisibleIssues());
        assertEquals("no-undef", r.issues.get(0).rule);
    }

    @Test
    public void parse_toolMissing_notVisible() throws Exception {
        JSONObject json = new JSONObject()
                .put("file", "a.js")
                .put("tool", "eslint")
                .put("ok", true)
                .put("tool_missing", true)
                .put("issues", new JSONArray());
        LintReport r = OrionLintClient.parse(json, "a.js");
        assertTrue(r.toolMissing);
        assertFalse(r.hasVisibleIssues());
    }

    @Test
    public void isLintable_webOnly() {
        assertTrue(OrionLintClient.isLintable("app.js"));
        assertTrue(OrionLintClient.isLintable("index.html"));
        assertTrue(OrionLintClient.isLintable("style.css"));
        assertFalse(OrionLintClient.isLintable("readme.md"));
        assertFalse(OrionLintClient.isLintable("Main.java"));
    }
}
