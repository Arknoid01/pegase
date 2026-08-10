package com.pegasuscorp.orbe.pcshell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class PcShellExecutionLogTest {

    @Test
    public void tailPreview_shortTextUnchanged() {
        String text = "hello\nworld";
        assertEquals(text, PcShellExecutionLog.tailPreview(text, 100));
    }

    @Test
    public void tailPreview_longTextKeepsEnd() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            sb.append('a');
        }
        sb.append("TAIL");
        String full = sb.toString();
        String preview = PcShellExecutionLog.tailPreview(full, 100);
        assertTrue(preview.startsWith("… ["));
        assertTrue(preview.endsWith("TAIL"));
        assertTrue(full.length() > preview.length());
    }

    @Test
    public void combineOutput_mergesStdoutStderr() {
        String combined = PcShellExecutionLog.combineOutput("out", "err");
        assertTrue(combined.contains("out"));
        assertTrue(combined.contains("--- stderr ---"));
        assertTrue(combined.contains("err"));
    }

    @Test
    public void entryJsonRoundTripWithOutputFull() throws Exception {
        PcShellExecutionLog.Entry e = new PcShellExecutionLog.Entry(
                1L, "id", "label", "cmd", 0, "preview", "full output", "", false);
        JSONObject o = e.toJson();
        PcShellExecutionLog.Entry back = PcShellExecutionLog.Entry.fromJson(o);
        assertEquals("full output", back.outputFull);
        assertEquals("preview", back.outputPreview);
        assertTrue(back.hasExpandableOutput());
    }

    @Test
    public void entryFromLegacyJsonUsesPreviewAsFull() throws Exception {
        JSONObject o = new JSONObject();
        o.put("atMs", 2L);
        o.put("commandId", "");
        o.put("label", "x");
        o.put("command", "ls");
        o.put("exitCode", 0);
        o.put("outputPreview", "only preview");
        o.put("error", "");
        o.put("cancelled", false);
        PcShellExecutionLog.Entry e = PcShellExecutionLog.Entry.fromJson(o);
        assertEquals("only preview", e.outputFull);
        assertFalse(e.hasExpandableOutput());
    }
}
