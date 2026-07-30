package com.pegasuscorp.orbe.orion;

import android.net.Uri;
import android.webkit.WebResourceResponse;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class OrionPagePreviewTest {

    @Test
    public void isPage_htmlAndSvg() {
        assertTrue(OrionPagePreview.isPage("index.html", "<h1>Hi</h1>"));
        assertTrue(OrionPagePreview.isPage("icon.svg", "<svg></svg>"));
        assertTrue(OrionPagePreview.isPage("out.txt", "<!DOCTYPE html><html></html>"));
        assertFalse(OrionPagePreview.isPage("Main.java", "class Main {}"));
    }

    @Test
    public void entryHtml_wrapsFragment() {
        String out = OrionPagePreview.entryHtml("card.html", "<div>x</div>");
        assertTrue(out.contains("<!DOCTYPE html>"));
        assertTrue(out.contains("<div>x</div>"));
    }

    @Test
    public void resolveLocal_servesCssSibling() {
        Map<String, String> files = new HashMap<>();
        files.put("style.css", "body{color:red}");
        WebResourceResponse r = OrionPagePreview.resolveLocal(
                Uri.parse("https://orion.preview.local/style.css"), files);
        assertNotNull(r);
        assertEquals("text/css", r.getMimeType());
    }
}
