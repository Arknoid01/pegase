package com.pegasuscorp.orbe.pcshell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.Color;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class AnsiTextTest {

    private static final int DEFAULT = Color.parseColor("#E8E8E8");

    @Test
    public void strip_removesSgr256Color() {
        String raw = "\u001B[38;5;39mPégase\u001B[0m";
        assertEquals("Pégase", AnsiText.strip(raw));
    }

    @Test
    public void strip_plainTextUnchanged() {
        assertEquals("hello", AnsiText.strip("hello"));
    }

    @Test
    public void containsEscape_detectsEsc() {
        assertTrue(AnsiText.containsEscape("\u001B[0m"));
        assertFalse(AnsiText.containsEscape("plain"));
    }

    @Test
    public void toSpannable_appliesColorSpan() {
        String raw = "\u001B[38;5;39mblue\u001B[0m end";
        SpannableStringBuilder sp = AnsiText.toSpannable(raw, DEFAULT);
        assertEquals("blue end", sp.toString());
        ForegroundColorSpan[] spans = sp.getSpans(0, sp.length(), ForegroundColorSpan.class);
        assertTrue(spans.length >= 2);
    }

    @Test
    public void strip_literalX1bEscapes() {
        String raw = "\\x1b[38;5;39mPégase\\x1b[0m";
        assertEquals("Pégase", AnsiText.strip(raw));
    }

    @Test
    public void toSpannable_literalX1bAppliesColor() {
        String raw = "\\x1b[38;5;39mblue\\x1b[0m";
        SpannableStringBuilder sp = AnsiText.toSpannable(raw, DEFAULT);
        assertEquals("blue", sp.toString());
        assertTrue(sp.getSpans(0, sp.length(), ForegroundColorSpan.class).length >= 1);
    }

    @Test
    public void toSpannable_bareSgrWithoutEscByte() {
        String raw = "[38;5;39mblue[0m";
        SpannableStringBuilder sp = AnsiText.toSpannable(raw, DEFAULT);
        assertEquals("blue", sp.toString());
    }

    @Test
    public void collapseCarriageReturn_overwritesLinePrefix() {
        String raw = "loading...\rdone\n";
        assertEquals("done\n", AnsiText.strip(raw));
    }

    @Test
    public void appendTo_skipsCursorSequences() {
        SpannableStringBuilder out = new SpannableStringBuilder();
        AnsiText.appendTo(out, "\u001B[2J\u001B[Hfastfetch", DEFAULT);
        assertEquals("fastfetch", out.toString());
    }
}
