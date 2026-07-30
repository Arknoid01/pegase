package com.pegasuscorp.orbe.orion;

import android.content.Context;

import com.pegasuscorp.orbe.orion.prompt.ResolvedTask;
import com.pegasuscorp.orbe.orion.search.OrionFileSearcher;
import com.pegasuscorp.orbe.rag.EmbeddingEngine;
import com.pegasuscorp.orbe.rag.VectorStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class PlanBuilderTest {

    private Context ctx;
    private OrionProjectStore store;
    private VectorStore vectors;

    @Before
    public void setUp() {
        ctx = RuntimeEnvironment.getApplication();
        EmbeddingEngine.resetForTests();
        OrionProjectStore.resetInstanceForTests();
        store = OrionProjectStore.get(ctx);
        File root = store.getProjectsRoot();
        File[] kids = root.listFiles();
        if (kids != null) {
            for (File f : kids) deleteRecursive(f);
        }
        OrionProjectStore.resetInstanceForTests();
        store = OrionProjectStore.get(ctx);
        vectors = new VectorStore(EmbeddingEngine.DIMENSIONS, true);
    }

    @After
    public void tearDown() {
        vectors.clear();
        vectors.close();
    }

    @Test
    public void parsePlanSteps_validJson_returnsSteps() {
        ResolvedTask parent = ResolvedTask.builder()
                .rawInput("refais toute l'UI boucherie")
                .build();
        String json = "["
                + "{\"summary\":\"Header + navigation\",\"file\":\"header.xml\","
                + "\"keyword\":\"header\",\"risk\":\"LOW\"},"
                + "{\"summary\":\"Liste des articles\",\"file\":\"ArticleList.java\","
                + "\"keyword\":\"articles\",\"risk\":\"HIGH\"}"
                + "]";
        List<PlanStep> steps = PlanBuilder.parsePlanSteps(json, parent);
        assertEquals(2, steps.size());
        assertEquals("Header + navigation", steps.get(0).summary);
        assertEquals("header.xml", steps.get(0).targetFile);
        assertEquals(TaskRisk.LOW, steps.get(0).risk);
        assertEquals(TaskRisk.HIGH, steps.get(1).risk);
    }

    @Test
    public void parsePlanSteps_capsAtEightSteps() {
        ResolvedTask parent = ResolvedTask.builder().rawInput("big").build();
        StringBuilder json = new StringBuilder("[");
        for (int i = 1; i <= 10; i++) {
            if (i > 1) json.append(',');
            json.append("{\"summary\":\"Step ").append(i).append("\"}");
        }
        json.append(']');
        assertEquals(8, PlanBuilder.parsePlanSteps(json.toString(), parent).size());
    }

    @Test
    public void parsePlanSteps_invalidJson_fallbackSingleStep() {
        ResolvedTask parent = ResolvedTask.builder()
                .rawInput("refais tout le projet")
                .build();
        List<PlanStep> steps = PlanBuilder.parsePlanSteps("not json", parent);
        assertEquals(1, steps.size());
        assertEquals("refais tout le projet", steps.get(0).summary);
        assertEquals(TaskRisk.MEDIUM, steps.get(0).risk);
    }

    @Test
    public void computeGlobalRisk_returnsMaxRisk() {
        List<PlanStep> steps = List.of(
                new PlanStep(1, "A", null, null, TaskRisk.LOW),
                new PlanStep(2, "B", null, null, TaskRisk.HIGH),
                new PlanStep(3, "C", null, null, TaskRisk.MEDIUM));
        assertEquals(TaskRisk.HIGH, PlanBuilder.computeGlobalRisk(steps));
    }

    @Test
    public void build_criticalFileDetected_marksStepCritical() throws Exception {
        store.createProject("orbe");
        store.setActive("orbe");
        store.saveFile("PegaseSession.java",
                "public class PegaseSession {}\n", false, false);

        OrionFileSearcher searcher = new OrionFileSearcher(store, vectors);
        PlanBuilder builder = new PlanBuilder(searcher, store);
        ResolvedTask parent = ResolvedTask.builder()
                .rawInput("refais toute l'architecture Pégase")
                .complexity(TaskComplexity.MASSIVE)
                .build();

        String json = "[{\"summary\":\"Toucher PegaseSession\","
                + "\"file\":\"PegaseSession.java\",\"keyword\":\"PegaseSession\","
                + "\"risk\":\"HIGH\"}]";

        ExecutionPlan plan = builder.build(ctx, parent, prompt -> json);
        assertEquals(1, plan.steps.size());
        assertEquals(TaskRisk.CRITICAL, plan.steps.get(0).risk);
        assertEquals(TaskRisk.CRITICAL, plan.globalRisk);
        assertEquals("PegaseSession.java", plan.steps.get(0).targetFile);
    }

    @Test
    public void toTaskChunks_approvedPlan_producesSimpleChunks() throws Exception {
        store.createProject("boucherie");
        store.setActive("boucherie");
        OrionFileSearcher searcher = new OrionFileSearcher(store, vectors);
        PlanBuilder builder = new PlanBuilder(searcher, store);
        ResolvedTask parent = ResolvedTask.builder()
                .rawInput("refais toute l'UI boucherie")
                .build();
        List<PlanStep> steps = List.of(
                new PlanStep(1, "Header", "header.xml", "header", TaskRisk.LOW),
                new PlanStep(2, "Styles", "styles.xml", "styles", TaskRisk.MEDIUM));
        ExecutionPlan plan = new ExecutionPlan("Refonte UI", steps, TaskRisk.MEDIUM);
        plan.status = ExecutionPlan.PlanStatus.APPROVED;

        List<TaskChunk> chunks = builder.toTaskChunks(ctx, plan, parent);
        assertEquals(2, chunks.size());
        assertEquals(TaskComplexity.SIMPLE, chunks.get(0).task.complexity);
        assertEquals(TaskRisk.LOW, chunks.get(0).task.risk);
        assertEquals(2, chunks.get(1).index);
        assertEquals(2, chunks.get(1).total);

        OrionChunkSession session = new OrionChunkSession(chunks);
        assertEquals("Header", session.current().summary);
        assertTrue(session.hasNext());
    }

    @Test
    public void executionPlan_toReadableText_includesStepsAndRisk() {
        List<PlanStep> steps = List.of(
                new PlanStep(1, "Header + navigation", "header.xml", "header", TaskRisk.LOW),
                new PlanStep(2, "Session Pégase", "PegaseSession.java", null, TaskRisk.CRITICAL));
        ExecutionPlan plan = new ExecutionPlan("Refonte UI boucherie", steps, TaskRisk.CRITICAL);
        String text = plan.toReadableText();
        assertTrue(text.contains("Refonte UI boucherie"));
        assertTrue(text.contains("header.xml"));
        assertTrue(text.contains("Fichier critique"));
        assertTrue(text.contains("Risque global : CRITICAL"));
        assertTrue(text.contains("On y va dans cet ordre ?"));
    }

    @Test
    public void executionPlan_statusDefaultsPending() {
        ExecutionPlan plan = new ExecutionPlan("Test",
                List.of(new PlanStep(1, "A", null, null, TaskRisk.LOW)),
                TaskRisk.LOW);
        assertEquals(ExecutionPlan.PlanStatus.PENDING, plan.status);
    }

    private static void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
