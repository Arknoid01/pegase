package com.pegasuscorp.orbe.bureau;

import org.junit.Test;

import static org.junit.Assert.*;

public class BureauMarkdownHtmlTest {

    @Test
    public void toHtml_rendersHeadingAndTask() {
        String html = BureauMarkdownHtml.toHtml("# Plan\n\n## Dev\n- [ ] Scanner\n- [x] RAG\n");
        assertTrue(html.contains("<h1>Plan</h1>"));
        assertTrue(html.contains("<h2>Dev</h2>"));
        assertTrue(html.contains("Scanner"));
        assertTrue(html.contains("checked"));
        assertTrue(html.contains("RAG"));
    }

    @Test
    public void toHtml_rendersMarkdownTable() {
        String md = "| Outil | État |\n|---|---|\n| PegaseSession | ✅ |\n| Orion | ❌ |\n";
        String html = BureauMarkdownHtml.toHtml(md);
        assertTrue(html.contains("<table>"));
        assertTrue(html.contains("<th>Outil</th>") || html.contains("Outil"));
        assertTrue(html.contains("PegaseSession"));
        assertTrue(html.contains("<td>"));
    }

    @Test
    public void toHtml_rendersMermaidBlockAndCdn() {
        String md = "## Archi\n\n```mermaid\ngraph TD\n    A --> B\n```\n";
        String html = BureauMarkdownHtml.toHtml(md);
        assertTrue(html.contains("class=\"mermaid\""));
        assertTrue(html.contains("graph TD"));
        assertTrue(html.contains("A --> B"));
        assertTrue(html.contains("cdn.jsdelivr.net/npm/mermaid"));
        assertTrue(html.contains("mermaid.initialize"));
    }

    @Test
    public void toHtml_plainCodeFence_notMermaid() {
        String html = BureauMarkdownHtml.toHtml("```java\nint x = 1;\n```\n");
        assertTrue(html.contains("<pre><code>"));
        assertFalse(html.contains("class=\"mermaid\""));
        assertTrue(html.contains("int x = 1;"));
    }
}
