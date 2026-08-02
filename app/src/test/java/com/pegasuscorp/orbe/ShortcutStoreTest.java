package com.pegasuscorp.orbe;

import org.junit.Test;

import static org.junit.Assert.*;

public class ShortcutStoreTest {

    @Test
    public void legacyPackage_roundTrip() {
        ShortcutStore.Slot s = ShortcutStore.parse("com.spotify.music");
        assertTrue(s.isApp());
        assertEquals("com.spotify.music", s.packageName);
        ShortcutStore.Slot again = ShortcutStore.parse(ShortcutStore.serialize(s));
        assertTrue(again.isApp());
        assertEquals("com.spotify.music", again.packageName);
    }

    @Test
    public void webSlot_roundTripAndNormalize() {
        ShortcutStore.Slot s = ShortcutStore.Slot.web("Cursor", "cursor.com");
        assertTrue(s.isWeb());
        assertEquals("Cursor", s.label);
        assertEquals("https://cursor.com", s.url);
        assertTrue(ShortcutStore.isValidHttpUrl(s.url));

        String json = ShortcutStore.serialize(s);
        assertTrue(json.contains("\"type\":\"web\""));
        ShortcutStore.Slot again = ShortcutStore.parse(json);
        assertTrue(again.isWeb());
        assertEquals("Cursor", again.label);
        assertEquals("https://cursor.com", again.url);
    }

    @Test
    public void normalizeUrl_variants() {
        assertEquals("https://a.com", ShortcutStore.normalizeUrl("a.com"));
        assertEquals("https://www.a.com", ShortcutStore.normalizeUrl("www.a.com"));
        assertEquals("https://x.io/y", ShortcutStore.normalizeUrl("https://x.io/y"));
        assertFalse(ShortcutStore.isValidHttpUrl("not a url"));
    }
}
