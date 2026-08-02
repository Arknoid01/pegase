package com.pegasuscorp.orbe.voice;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

public class KwsModelStoreTest {

    @Test
    public void dropsLineWithUnknownToken() {
        Set<String> vocab = new HashSet<>();
        vocab.add("\u2581P");
        vocab.add("E");
        vocab.add("G");
        vocab.add("AS");
        String content = "\u2581P E G AS E :6.0 #0.05 @PEGASE\n"
                + "\u2581PE G AS E :5.5 #0.05 @BAD\n";
        String filtered = KwsModelStore.filterValidKeywordLines(content, vocab);
        assertTrue(filtered.contains("@PEGASE"));
        assertFalse(filtered.contains("@BAD"));
        assertFalse(filtered.contains("\u2581PE"));
    }

    @Test
    public void firstMissingTokenFindsUnknown() {
        Set<String> vocab = new HashSet<>();
        vocab.add("\u2581P");
        vocab.add("E");
        assertEquals("\u2581PE",
                KwsModelStore.firstMissingToken("\u2581PE G :1.0 #0.1 @X", vocab));
        assertNull(KwsModelStore.firstMissingToken("\u2581P E :1.0 #0.1 @OK", vocab));
    }
}
