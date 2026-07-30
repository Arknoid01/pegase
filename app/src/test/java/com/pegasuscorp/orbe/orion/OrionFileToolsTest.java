package com.pegasuscorp.orbe.orion;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class OrionFileToolsTest {

    @Test
    public void toolSchemas_hasWriteReadAppend() throws Exception {
        JSONArray tools = OrionFileTools.toolSchemas();
        assertEquals(3, tools.length());
        String names = tools.toString();
        assertTrue(names.contains("write_file"));
        assertTrue(names.contains("read_file"));
        assertTrue(names.contains("append_file"));
    }

    @Test
    public void parseToolCalls_fromOllamaMessage() throws Exception {
        JSONObject msg = new JSONObject()
                .put("role", "assistant")
                .put("content", "")
                .put("tool_calls", new JSONArray()
                        .put(new JSONObject()
                                .put("id", "c1")
                                .put("function", new JSONObject()
                                        .put("name", "write_file")
                                        .put("arguments", new JSONObject()
                                                .put("filename", "index.html")
                                                .put("content", "<h1>hi</h1>")))));
        List<JSONObject> calls = OrionFileTools.parseToolCalls(msg);
        assertEquals(1, calls.size());
        assertEquals("write_file", calls.get(0).getString("name"));
        assertEquals("index.html",
                calls.get(0).getJSONObject("arguments").getString("filename"));
    }

    @Test
    public void toFenceDump_html() {
        List<OrionFileTools.WriteResult> writes = new ArrayList<>();
        writes.add(new OrionFileTools.WriteResult("index.html", "<html></html>", "ok"));
        String dump = OrionFileTools.toFenceDump(writes);
        assertTrue(dump.contains("```html:index.html"));
        assertTrue(dump.contains("<html></html>"));
    }

    @Test
    public void isWebAsset_andPickMain() {
        assertTrue(OrionFileTools.isWebAsset("ball.js"));
        assertTrue(OrionFileTools.isWebAsset("style.css"));
        assertFalse(OrionFileTools.isWebAsset("Main.java"));
        List<OrionFileSession.OrionFile> files = new ArrayList<>();
        files.add(new OrionFileSession.OrionFile("app.js", "x",
                OrionFileSession.FileStatus.PENDING));
        files.add(new OrionFileSession.OrionFile("index.html", "<html/>",
                OrionFileSession.FileStatus.PENDING));
        assertEquals("index.html", OrionFileTools.pickMainPage(files));
    }

    @Test
    public void formatLintDirective_askFix() {
        List<LintReport.LintIssue> issues = new ArrayList<>();
        issues.add(new LintReport.LintIssue(12, 1, "error", "no-undef",
                "'pauseBtn' is not defined"));
        issues.add(new LintReport.LintIssue(30, 1, "warning", "x", "warn only"));
        LintReport r = new LintReport("timer.js", "eslint", false, false, issues);
        String msg = OrionFileTools.formatLintDirective(r, true);
        assertTrue(msg.contains("timer.js écrit — 1 erreur"));
        assertTrue(msg.contains("ligne 12"));
        assertTrue(msg.contains("no-undef"));
        assertTrue(msg.contains("Corrige ces erreurs avec write_file."));
        assertFalse(msg.contains("warn only"));
    }

    @Test
    public void formatLintDirective_stopAfterMax() {
        List<LintReport.LintIssue> issues = new ArrayList<>();
        issues.add(new LintReport.LintIssue(1, 0, "error", "x", "boom"));
        LintReport r = new LintReport("a.js", "eslint", false, false, issues);
        String msg = OrionFileTools.formatLintDirective(r, false);
        assertTrue(msg.contains("Correction lint arrêtée"));
        assertFalse(msg.contains("Corrige ces erreurs"));
    }
}
