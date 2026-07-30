package com.pegasuscorp.orbe.bureau;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class BureauMarkdownOutlineTest {

    @Test
    public void headings_and_tasks() {
        String md = "# Plan\n## Objectifs\n- but\n## Tâches\n- [ ] Faire A\n- [x] Faire B\n";
        List<BureauMarkdownOutline.HeadingItem> heads = BureauMarkdownOutline.headings(md);
        assertTrue(heads.size() >= 3);
        List<BureauMarkdownOutline.TaskItem> tasks = BureauMarkdownOutline.tasks(md);
        assertEquals(2, tasks.size());
        assertFalse(tasks.get(0).done);
        assertTrue(tasks.get(1).done);
        assertEquals("Faire A", tasks.get(0).text);
    }

    @Test
    public void toggleTaskAtLine() {
        String md = "## Tâches\n- [ ] Todo\n";
        String toggled = BureauMarkdownOutline.toggleTaskAtLine(md, 1);
        assertTrue(toggled.contains("- [x] Todo"));
        String back = BureauMarkdownOutline.toggleTaskAtLine(toggled, 1);
        assertTrue(back.contains("- [ ] Todo"));
    }

    @Test
    public void insertUnderSection_createsOrPrepends() {
        String md = "# Plan\n## Notes / recherche\n\nold\n";
        String next = BureauMarkdownOutline.insertUnderSection(md, "Notes / recherche", "- new");
        assertTrue(next.contains("- new"));
        assertTrue(next.indexOf("- new") < next.indexOf("old"));

        String bare = "# Only\n";
        String with = BureauMarkdownOutline.insertUnderSection(bare, "Tâches", "- [ ] X");
        assertTrue(with.contains("## Tâches"));
        assertTrue(with.contains("- [ ] X"));
    }

    @Test
    public void appendHistorique() {
        String md = BureauPlanTemplate.namedPlan("P");
        String next = BureauMarkdownOutline.appendHistorique(md, "Structuré");
        assertTrue(next.contains("Structuré"));
        assertTrue(next.contains("## Historique Pégase"));
    }
}
