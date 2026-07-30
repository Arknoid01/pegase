package com.pegasuscorp.orbe.copilot;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class OcrFallbackTest {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        CopilotPrefs.setScreenAnalysisEnabled(ctx, true);
        CopilotPrefs.setWhitelist(ctx, java.util.Collections.singleton("com.example.game"));
    }

    @Test
    public void needsFallback_whenSnapshotEmpty() {
        writeSnapshot(0);
        assertTrue(OcrFallback.needsFallback(ctx));
    }

    @Test
    public void needsFallback_whenEnoughNodes() throws Exception {
        JSONArray nodes = new JSONArray();
        nodes.put(node("A"));
        nodes.put(node("B"));
        writeSnapshot(nodes);
        assertFalse(OcrFallback.needsFallback(ctx));
    }

    @Test
    public void tryEnrich_skipsWithoutCapturePermission() {
        writeSnapshot(0);
        assertFalse(OcrFallback.tryEnrich(ctx, "com.example.game"));
    }

    private void writeSnapshot(int nodeCount) {
        try {
            JSONArray nodes = new JSONArray();
            for (int i = 0; i < nodeCount; i++) {
                nodes.put(node("text" + i));
            }
            writeSnapshot(nodes);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void writeSnapshot(JSONArray nodes) throws Exception {
        JSONObject doc = new JSONObject();
        doc.put("package", "com.example.game");
        doc.put("nodes", nodes);
        File dir = new File(ctx.getFilesDir(), "copilot");
        if (!dir.exists()) dir.mkdirs();
        File out = new File(dir, "a11y_snapshot.json");
        try (FileOutputStream fos = new FileOutputStream(out)) {
            fos.write(doc.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private static JSONObject node(String text) throws Exception {
        JSONObject o = new JSONObject();
        o.put("text", text);
        o.put("clickable", false);
        o.put("left", 0);
        o.put("top", 0);
        o.put("right", 10);
        o.put("bottom", 10);
        return o;
    }
}
