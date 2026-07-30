package com.pegasuscorp.orbe.bureau;

import org.junit.Test;

import static org.junit.Assert.*;

public class BureauMarkdownParserTest {

    @Test
    public void parse_extractsSpeakAndMarkdown() {
        String raw = "> C'est noté.\n## Dev\n- [ ] Tâche\n";
        BureauMarkdownParser.Parsed p = BureauMarkdownParser.parse(raw);
        assertEquals("C'est noté.", p.speak);
        assertTrue(p.markdown.contains("## Dev"));
        assertTrue(p.markdown.contains("- [ ] Tâche"));
        assertFalse(p.replaceAll);
    }

    @Test
    public void parse_replaceAllDocument() {
        String raw = "---DOCUMENT---\n# Titre\n\nContenu\n";
        BureauMarkdownParser.Parsed p = BureauMarkdownParser.parse(raw);
        assertTrue(p.replaceAll);
        assertTrue(p.markdown.startsWith("# Titre"));
    }

    @Test
    public void parse_pegaseAnswerLine_staysInMarkdownAndSpeak() {
        String raw = "> 💡 Pégase : Oui, le brief agrège météo et diag.\n";
        BureauMarkdownParser.Parsed p = BureauMarkdownParser.parse(raw);
        assertTrue(p.markdown.contains("> 💡 Pégase : Oui, le brief agrège météo et diag."));
        assertEquals("Oui, le brief agrège météo et diag.", p.speak);
    }

    @Test
    public void parse_stripsCodeFence() {
        String raw = "```markdown\n> ok\n- item\n```";
        BureauMarkdownParser.Parsed p = BureauMarkdownParser.parse(raw);
        assertEquals("ok", p.speak);
        assertEquals("- item", p.markdown);
    }

    @Test
    public void parse_stripsTrailingBracketsJunk() {
        String raw = "> C'est noté.\n## Dev\n- [ ] Tâche\n]]]\n";
        BureauMarkdownParser.Parsed p = BureauMarkdownParser.parse(raw);
        assertFalse(p.markdown.contains("]]]"));
        assertTrue(p.markdown.contains("## Dev"));
        assertEquals("C'est noté.", p.speak);
    }

    @Test
    public void parse_stripsDocumentEchoWithoutReplaceAll() {
        String doc = "# Plan\n\n## Objectifs\n- A\n\n## Notes\n- old\n";
        String raw = "> J'ai ajouté une note.\n" + doc + "\n- nouvelle idée\n";
        BureauMarkdownParser.Parsed p = BureauMarkdownParser.parse(raw, doc);
        assertFalse(p.replaceAll);
        assertFalse(p.markdown.contains("## Objectifs"));
        assertTrue(p.markdown.contains("nouvelle idée"));
        assertEquals("J'ai ajouté une note.", p.speak);
    }

    @Test
    public void parse_preservesMermaidFence() {
        String raw = "> Voici le schéma.\n## Archi\n\n```mermaid\ngraph TD\n    A --> B\n```\n";
        BureauMarkdownParser.Parsed p = BureauMarkdownParser.parse(raw);
        assertEquals("Voici le schéma.", p.speak);
        assertTrue(p.markdown.contains("```mermaid"));
        assertTrue(p.markdown.contains("graph TD"));
        assertTrue(p.markdown.contains("```"));
        assertTrue(p.markdown.contains("A --> B"));
    }

    @Test
    public void parse_keepsOuterMermaidWrapper() {
        String raw = "```mermaid\ngraph LR\n    X --> Y\n```";
        BureauMarkdownParser.Parsed p = BureauMarkdownParser.parse(raw);
        assertTrue(p.markdown.contains("```mermaid"));
        assertTrue(p.markdown.contains("X --> Y"));
    }

    @Test
    public void parse_exactEchoBecomesEmptyMarkdown() {
        String doc = "# Titre\n\nContenu utile ici.\n";
        BureauMarkdownParser.Parsed p = BureauMarkdownParser.parse(doc, doc);
        assertEquals("", p.markdown);
        assertFalse(p.replaceAll);
    }
}
