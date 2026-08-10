package com.pegasuscorp.orbe.pcshell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.text.Spanned;
import android.text.style.ForegroundColorSpan;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class PcTerminalScrollbackTest {

    @Test
    public void splitsIntoMultipleChunksAroundFourK() {
        PcTerminalScrollback sb = new PcTerminalScrollback();
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 9000; i++) {
            big.append('x');
            if (i % 80 == 79) big.append('\n');
        }
        sb.currentChunk().append(big);
        sb.afterAppend();
        assertTrue(sb.getChunkCount() >= 2);
        assertEquals(big.length(), sb.length());
    }

    @Test
    public void preservesSpansWhenSplitting() {
        PcTerminalScrollback sb = new PcTerminalScrollback();
        int start = sb.currentChunk().length();
        String line = "a".repeat(5000) + "\n";
        sb.currentChunk().append(line);
        sb.currentChunk().setSpan(new ForegroundColorSpan(0xFFFF0000),
                start, start + line.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.afterAppend();
        assertTrue(sb.getChunkCount() >= 2);
    }
}
