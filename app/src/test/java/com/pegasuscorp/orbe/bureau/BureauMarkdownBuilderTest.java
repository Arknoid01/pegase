package com.pegasuscorp.orbe.bureau;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.*;

public class BureauMarkdownBuilderTest {

    @Test
    public void render_sportExample_hasStrictSections() {
        BureauProject p = BureauProjectStoreTest.sampleSport();
        BureauProject.Reference ref = new BureauProject.Reference();
        ref.id = "r1";
        ref.title = "Timers avec Jetpack Compose";
        ref.path = "research/sport-timer-compose.md";
        ref.createdAt = p.createdAt;
        p.references.add(ref);

        String md = BureauMarkdownBuilder.render(p);
        assertTrue(md.startsWith("# Sport\n"));
        assertTrue(md.contains("## Vision\n"));
        assertTrue(md.contains("## Objectifs\n"));
        assertTrue(md.contains("## Décisions\n"));
        assertTrue(md.contains("## Hypothèses\n"));
        assertTrue(md.contains("## À vérifier\n"));
        assertTrue(md.contains("## Tâches\n"));
        assertTrue(md.contains("## Questions ouvertes\n"));
        assertTrue(md.contains("## Références\n"));
        assertTrue(md.contains("## Historique\n"));

        assertTrue(md.contains("- ✓ ["));
        assertTrue(md.contains("] Utiliser Jetpack Compose."));
        assertTrue(md.contains("- ? Ajouter des notifications"));
        assertTrue(md.contains("- ! Vérifier la meilleure gestion"));
        assertTrue(md.contains("- [ ] Concevoir la maquette."));
        assertTrue(md.contains("[Timers avec Jetpack Compose](../research/sport-timer-compose.md)"));
        assertTrue(md.contains("Projet créé après entretien avec Pégase."));
        assertFalse(md.contains("Notes / recherche"));
        assertFalse(md.toLowerCase().contains("pegase a répondu"));
    }

    @Test
    public void render_emptyProject_stillHasHeadings() {
        BureauProject p = new BureauProject();
        p.title = "Vide";
        String md = BureauMarkdownBuilder.render(p);
        assertTrue(md.contains("# Vide"));
        assertTrue(md.contains("## Vision"));
        assertTrue(md.contains("## Historique"));
    }
}
