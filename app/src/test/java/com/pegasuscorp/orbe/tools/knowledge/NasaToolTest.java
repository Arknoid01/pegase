package com.pegasuscorp.orbe.tools.knowledge;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.pegasuscorp.orbe.tools.ToolResult;

/**
 * Vérifie le format APOD (URL standard préférée) via le parsing du résultat filaire.
 */
public class NasaToolTest {

    @Test
    public void imageUrl_wireRoundTrip() {
        ToolResult r = ToolResult.imageUrl(
                "NASA APOD du jour\nTitre : Test\nExplication : hello",
                "https://apod.nasa.gov/apod/image/2607/Genesisimpact_nasa_960.jpg");
        assertEquals(ToolResult.Kind.IMAGE_URL, r.kind);
        assertTrue(r.wireText().startsWith("NASA_IMAGE:https://apod.nasa.gov"));
        assertEquals("https://apod.nasa.gov/apod/image/2607/Genesisimpact_nasa_960.jpg",
                NasaReplyHelper.extractImageUrl(r.wireText()));
        assertFalse(NasaReplyHelper.extractEnglishText(r.wireText()).isEmpty());
    }

    @Test
    public void fromWire_emptyUrl() {
        assertEquals("", NasaReplyHelper.extractImageUrl("pas une image"));
        assertEquals("", NasaReplyHelper.extractImageUrl(null));
    }
}
