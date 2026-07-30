package com.pegasuscorp.orbe.orion;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class OrionFileParserTest {

    @Test
    public void parse_equalsHeaders() {
        String text = "=== index.html ===\n"
                + "<html></html>\n\n"
                + "=== style.css ===\n"
                + "body{}\n\n"
                + "=== ball.js ===\n"
                + "const x=1;\n";
        List<OrionFileParser.ParsedFile> files = OrionFileParser.parse(text);
        assertEquals(3, files.size());
        assertEquals("index.html", files.get(0).path);
        assertTrue(files.get(0).content.contains("<html>"));
        assertEquals("style.css", files.get(1).path);
        assertEquals("ball.js", files.get(2).path);
    }

    @Test
    public void parse_markdownFencesFallback() {
        String text = "Voici :\n\n```java:Main.java\nclass Main {}\n```\n"
                + "```css:app.css\n.a{}\n```\n";
        List<OrionFileParser.ParsedFile> files = OrionFileParser.parse(text);
        assertTrue(files.size() >= 2);
        assertEquals("Main.java", files.get(0).path);
    }

    @Test
    public void session_validateAll() {
        OrionFileSession s = new OrionFileSession("balle");
        s.addFile("a.html", "<a>");
        s.addFile("b.css", ".b{}");
        assertEquals(2, s.getPendingFiles().size());
        s.validateAll();
        assertEquals(2, s.getReadyFiles().size());
        assertEquals(0, s.getPendingFiles().size());
    }
}
