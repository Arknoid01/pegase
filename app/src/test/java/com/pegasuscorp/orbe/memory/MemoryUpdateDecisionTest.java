package com.pegasuscorp.orbe.memory;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class MemoryUpdateDecisionTest {

    @Test
    public void parse_add() {
        MemoryUpdateDecision d = MemoryUpdateDecision.parse(
                "{\"op\":\"ADD\",\"reason\":\"nouveau\"}");
        assertEquals(MemoryUpdateDecision.Op.ADD, d.op);
    }

    @Test
    public void parse_update() {
        MemoryUpdateDecision d = MemoryUpdateDecision.parse(
                "{\"op\":\"UPDATE\",\"id\":1,\"content\":\"RDV 17h\",\"reason\":\"heure\"}");
        assertEquals(MemoryUpdateDecision.Op.UPDATE, d.op);
        assertEquals(1, d.targetIndex);
        assertEquals("RDV 17h", d.updatedContent);
    }

    @Test
    public void parse_delete() {
        MemoryUpdateDecision d = MemoryUpdateDecision.parse(
                "{\"op\":\"DELETE\",\"id\":0,\"reason\":\"contredit\"}");
        assertEquals(MemoryUpdateDecision.Op.DELETE, d.op);
        assertEquals(0, d.targetIndex);
    }

    @Test
    public void parse_noop_and_fence() {
        MemoryUpdateDecision d = MemoryUpdateDecision.parse(
                "```json\n{\"op\":\"NOOP\",\"reason\":\"doublon\"}\n```");
        assertEquals(MemoryUpdateDecision.Op.NOOP, d.op);
    }

    @Test
    public void parse_update_without_content_becomes_noop() {
        MemoryUpdateDecision d = MemoryUpdateDecision.parse(
                "{\"op\":\"UPDATE\",\"id\":0}");
        assertEquals(MemoryUpdateDecision.Op.NOOP, d.op);
    }

    @Test
    public void formatNeighbors_indexes() {
        MemoryEntry a = new MemoryEntry("session", "RDV 15h", 0.7, "2026-01-01");
        MemoryEntry b = new MemoryEntry("session", "RDV 17h", 0.7, "2026-01-01");
        String f = MemoryUpdateDecision.formatNeighbors(Arrays.asList(a, b));
        assertTrue(f.contains("0."));
        assertTrue(f.contains("1."));
        assertTrue(f.contains("15h"));
    }

    @Test
    public void buildPrompt_includesCandidate() {
        String p = MemoryUpdateDecision.buildPrompt(
                "Préfère le thé", "session", Collections.emptyList());
        assertTrue(p.contains("Préfère le thé"));
        assertTrue(p.contains("ADD|UPDATE|DELETE|NOOP"));
    }
}
