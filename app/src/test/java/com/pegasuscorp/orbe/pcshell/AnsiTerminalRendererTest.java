package com.pegasuscorp.orbe.pcshell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.Color;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class AnsiTerminalRendererTest {

    private static final int FG = Color.parseColor("#E8E8E8");

    @Test
    public void render_twoColumnsWithCup() {
        String raw = "\u001B[1;1Hleft\u001B[1;40Hright";
        String out = AnsiTerminalRenderer.render(raw, 80, FG).toString();
        assertTrue(out.startsWith("left"));
        assertTrue(out.contains("right"));
        int leftIdx = out.indexOf("left");
        int rightIdx = out.indexOf("right");
        assertTrue(rightIdx > leftIdx + 4);
    }

    @Test
    public void render_chaPositionsInfoColumn() {
        String raw = "logo\u001B[40Ginfo";
        String out = AnsiTerminalRenderer.render(raw, 80, FG).toString();
        assertTrue(out.contains("logo"));
        assertTrue(out.contains("info"));
        assertTrue(out.indexOf("info") >= 39);
    }

    @Test
    public void needsGridRender_detectsCursorCodes() {
        assertTrue(AnsiTerminalRenderer.needsGridRender("x\u001B[10G"));
        assertFalse(AnsiTerminalRenderer.needsGridRender("plain text"));
    }
}
