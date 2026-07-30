package com.pegasuscorp.orbe.fs;

import org.junit.Test;

import static org.junit.Assert.*;

public class UriDisplayNamesTest {

    @Test
    public void stripPathNoise_keepsRealFilename() {
        assertEquals("plan-v2.md", UriDisplayNames.stripPathNoise("plan-v2.md"));
        assertEquals("plan-v2.md",
                UriDisplayNames.stripPathNoise("primary:Download/plan-v2.md"));
    }

    @Test
    public void stripPathNoise_documentIdFallback() {
        // Sans DISPLAY_NAME, l'ancien bug : msf:13286 → "13286"
        assertEquals("13286", UriDisplayNames.stripPathNoise("msf:13286"));
    }
}
