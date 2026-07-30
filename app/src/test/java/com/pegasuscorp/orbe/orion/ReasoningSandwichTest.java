package com.pegasuscorp.orbe.orion;

import com.pegasuscorp.orbe.orion.prompt.PromptCompiler;
import com.pegasuscorp.orbe.orion.prompt.PromptAmbiguityAnalyzer;
import com.pegasuscorp.orbe.orion.prompt.ResolvedTask;
import com.pegasuscorp.orbe.orion.qa.OrionQaChecker;
import com.pegasuscorp.orbe.orion.qa.OrionQaReport;
import com.pegasuscorp.orbe.orion.search.FileLocation;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class ReasoningSandwichTest {

    private static FileLocation sampleLocation() {
        return new FileLocation("ball.js", 23,
                "  let x = 1;\n"
                        + "→ let particleCount = 50;\n"
                        + "  return particleCount;\n");
    }

    @Test
    public void planningPrompt_injectsSnippetWhenFileLocationPresent() {
        ResolvedTask task = ResolvedTask.builder()
                .rawInput("plus de particules")
                .mission("plus de particules")
                .fileLocation(sampleLocation())
                .build();
        String prompt = PromptCompiler.buildPlanningPrompt(task);
        assertTrue(prompt.contains("=== Code concerné ==="));
        assertTrue(prompt.contains("ball.js"));
        assertTrue(prompt.contains("particleCount = 50"));
        assertTrue(prompt.contains("Planifie en tenant compte"));
    }

    @Test
    public void planningPrompt_worksWithoutFileLocation() {
        ResolvedTask task = ResolvedTask.builder()
                .rawInput("plus de particules")
                .build();
        String prompt = PromptCompiler.buildPlanningPrompt(task);
        assertFalse(prompt.contains("=== Code concerné ==="));
        assertTrue(prompt.contains("plus de particules"));
    }

    @Test
    public void analysisPrompt_injectsSnippet() {
        String prompt = PromptAmbiguityAnalyzer.buildAnalysisPrompt(
                "plus de particules", "Projet : demo", null, null, false,
                sampleLocation());
        assertTrue(prompt.contains("=== Code concerné ==="));
        assertTrue(prompt.contains("ball.js"));
        assertTrue(prompt.contains("=== Demande ==="));
    }

    @Test
    public void verificationPrompt_injectsOriginalSnippet() {
        ResolvedTask task = ResolvedTask.builder()
                .rawInput("plus de particules")
                .fileLocation(sampleLocation())
                .risk(TaskRisk.MEDIUM)
                .build();
        String prompt = OrionQaChecker.buildVerificationPrompt(
                task, "@@ -23 +23 @@\n-particleCount = 50\n+particleCount = 150", false);
        assertTrue(prompt.contains("=== Code original ==="));
        assertTrue(prompt.contains("particleCount = 50"));
        assertTrue(prompt.contains("=== Diff généré par Orion ==="));
        assertTrue(prompt.contains("CONFORME"));
    }

    @Test
    public void lowRisk_skipsSemanticJudge() {
        ResolvedTask task = ResolvedTask.builder()
                .mission("patch particules")
                .objective("augmenter particleCount")
                .action("particleCount")
                .risk(TaskRisk.LOW)
                .fileLocation(sampleLocation())
                .build();
        Map<String, String> before = new LinkedHashMap<>();
        before.put("ball.js", "let particleCount = 50;\n");
        Map<String, String> after = new LinkedHashMap<>();
        after.put("ball.js", "let particleCount = 150;\n");

        AtomicInteger calls = new AtomicInteger(0);
        OrionQaReport report = OrionQaChecker.check(task, before, after,
                (m, d) -> {
                    calls.incrementAndGet();
                    return "NON_CONFORME : ne devrait pas être appelé";
                });
        assertTrue(report.isCompliant());
        assertEquals(0, calls.get());
    }

    @Test
    public void mediumRisk_callsSemanticJudge() {
        ResolvedTask task = ResolvedTask.builder()
                .mission("patch particules")
                .rawInput("plus de particules")
                .objective("augmenter particleCount")
                .action("particleCount")
                .risk(TaskRisk.MEDIUM)
                .fileLocation(sampleLocation())
                .build();
        Map<String, String> before = new LinkedHashMap<>();
        before.put("ball.js", "let particleCount = 50;\n");
        Map<String, String> after = new LinkedHashMap<>();
        after.put("ball.js", "let particleCount = 150;\n");

        AtomicInteger calls = new AtomicInteger(0);
        OrionQaReport report = OrionQaChecker.check(task, before, after,
                (m, d) -> {
                    calls.incrementAndGet();
                    assertTrue(m.contains("=== Diff généré par Orion ===")
                            || m.contains("Vérification courte"));
                    return "CONFORME";
                });
        assertTrue(report.isCompliant());
        assertEquals(1, calls.get());
    }

    @Test
    public void criticalRisk_usesFullVerificationPrompt() {
        ResolvedTask task = ResolvedTask.builder()
                .mission("patch PegaseSession")
                .rawInput("corriger PegaseSession")
                .risk(TaskRisk.CRITICAL)
                .fileLocation(new FileLocation("PegaseSession.java", 10, "class PegaseSession {}"))
                .build();
        Map<String, String> before = new LinkedHashMap<>();
        before.put("PegaseSession.java", "class PegaseSession { int x = 1; }\n");
        Map<String, String> after = new LinkedHashMap<>();
        after.put("PegaseSession.java", "class PegaseSession { int x = 2; }\n");

        AtomicInteger calls = new AtomicInteger(0);
        OrionQaChecker.check(task, before, after, (m, d) -> {
            calls.incrementAndGet();
            assertTrue(m.contains("approfondie") || m.contains("CRITICAL"));
            return "CONFORME";
        });
        assertEquals(1, calls.get());
    }

    @Test
    public void structural_extraFile_nonCompliantImmediately() {
        ResolvedTask task = ResolvedTask.builder()
                .mission("patch particules")
                .objective("particleCount")
                .action("particleCount")
                .risk(TaskRisk.LOW)
                .fileLocation(sampleLocation())
                .build();
        Map<String, String> before = new LinkedHashMap<>();
        before.put("ball.js", "let particleCount = 50;\n");
        before.put("styles.css", "body { color: #fff; }\n");
        Map<String, String> after = new LinkedHashMap<>();
        after.put("ball.js", "let particleCount = 150;\n");
        after.put("styles.css", "body { color: #000; }\n");

        AtomicInteger calls = new AtomicInteger(0);
        OrionQaReport report = OrionQaChecker.check(task, before, after,
                (m, d) -> {
                    calls.incrementAndGet();
                    return "CONFORME";
                });
        assertFalse(report.isCompliant());
        assertTrue(report.reason.toLowerCase().contains("hors scope")
                || report.reason.contains("styles.css"));
        assertEquals(0, calls.get());
    }

    @Test
    public void withoutFileLocation_sandwichStillWorks() {
        ResolvedTask task = ResolvedTask.builder()
                .mission("retouche mineure")
                .rawInput("retouche mineure")
                .risk(TaskRisk.MEDIUM)
                .build();
        Map<String, String> before = new LinkedHashMap<>();
        before.put("a.js", "x = 1;\n");
        Map<String, String> after = new LinkedHashMap<>();
        after.put("a.js", "x = 2;\n");

        OrionQaReport report = OrionQaChecker.check(task, before, after,
                (m, d) -> "CONFORME");
        assertTrue(report.isCompliant());
        String verify = OrionQaChecker.buildVerificationPrompt(task, "diff", false);
        assertFalse(verify.contains("=== Code original ==="));
    }
}
