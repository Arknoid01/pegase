package com.pegasuscorp.orbe.copilot;

import org.junit.Test;

import static org.junit.Assert.*;

public class EmojiNameMapTest {

    @Test
    public void expand_wholePhrase() {
        assertEquals("😂", EmojiNameMap.expand("Smiley qui rigole jaune"));
        assertEquals("😢", EmojiNameMap.expand("smiley qui pleure"));
        assertEquals("❤️", EmojiNameMap.expand("coeur"));
    }

    @Test
    public void expand_inlineKeepsSurroundingText() {
        String out = EmojiNameMap.expand("salut smiley qui rigole");
        assertTrue(out.contains("😂"));
        assertTrue(out.toLowerCase().contains("salut"));
    }

    @Test
    public void expand_unknownLeftAsIs() {
        assertEquals("bonjour", EmojiNameMap.expand("bonjour"));
    }
}
