package com.pegasuscorp.orbe.bureau;

import org.junit.Test;

import static org.junit.Assert.*;

public class BureauPlanTemplateTest {

    @Test
    public void dailyScratch_hasPlanSections() {
        String md = BureauPlanTemplate.dailyScratch();
        assertTrue(md.startsWith("# Bureau"));
        assertTrue(md.contains("## Vision"));
        assertTrue(md.contains("## Objectifs"));
        assertTrue(md.contains("## Décisions"));
        assertTrue(md.contains("## Tâches"));
        assertTrue(md.contains("- [ ]"));
        assertTrue(md.contains("## Questions ouvertes"));
        assertTrue(md.contains("## Notes / recherche"));
        assertTrue(md.contains("## Historique Pégase"));
    }

    @Test
    public void namedPlan_usesTitle() {
        String md = BureauPlanTemplate.namedPlan("Olympo");
        assertTrue(md.startsWith("# Olympo"));
        assertTrue(BureauPlanTemplate.looksLikePlan(md));
    }
}
