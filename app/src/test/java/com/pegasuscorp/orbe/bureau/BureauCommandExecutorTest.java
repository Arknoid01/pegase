package com.pegasuscorp.orbe.bureau;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class BureauCommandExecutorTest {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = RuntimeEnvironment.getApplication();
        BureauProject p = BureauProjectStoreTest.sampleSport();
        BureauProjectStore.save(ctx, p);
    }

    @Test
    public void appendAndCompleteTask_recordsSignificantHistory() {
        int histBefore = BureauProjectStore.load(ctx, "sport").history.size();
        BureauCommandExecutor.Result add = BureauCommandExecutor.appendTask(ctx, "sport", "Nouvelle tâche");
        assertTrue(add.ok);
        assertFalse(add.project.tasks.isEmpty());
        String taskId = null;
        for (BureauProject.Task t : add.project.tasks) {
            if ("Nouvelle tâche".equals(t.text)) taskId = t.id;
        }
        assertNotNull(taskId);

        BureauCommandExecutor.Result done = BureauCommandExecutor.completeTask(ctx, "sport", taskId);
        assertTrue(done.ok);
        assertTrue(done.project.history.size() > histBefore);
        String last = done.project.history.get(done.project.history.size() - 1).text;
        assertTrue(last.contains("terminée"));
        assertFalse(last.toLowerCase().contains("régénér"));
    }

    @Test
    public void promoteHypothesis_becomesConfirmed() {
        BureauCommandExecutor.Result r = BureauCommandExecutor.promoteHypothesis(ctx, "sport", "h1");
        assertTrue(r.ok);
        BureauProject.Decision d = null;
        for (BureauProject.Decision x : r.project.decisions) {
            if ("h1".equals(x.id)) d = x;
        }
        assertNotNull(d);
        assertEquals(BureauProject.Confidence.CONFIRMED, d.confidence);
        String md = BureauMarkdownBuilder.render(r.project);
        assertTrue(md.contains("notifications de rappel"));
        assertTrue(md.contains("## Décisions"));
    }

    @Test
    public void applyCommandsJson_completeById() {
        String taskId = BureauProjectStore.load(ctx, "sport").tasks.get(0).id;
        String json = "COMMANDS:\n[{\"op\":\"completeTask\",\"taskId\":\"" + taskId + "\"}]";
        BureauCommandExecutor.Result r = BureauCommandExecutor.applyCommandsJson(ctx, "sport", json);
        assertTrue(r.message, r.ok);
        assertTrue(r.project.tasks.get(0).done);
    }

    @Test
    public void appendResearch_createsFileAndReference() {
        BureauCommandExecutor.Result r = BureauCommandExecutor.appendResearch(
                ctx, "sport", "Timers Compose", "Notes sur CountDownTimer.");
        assertTrue(r.ok);
        assertFalse(r.project.references.isEmpty());
        String path = r.project.references.get(r.project.references.size() - 1).path;
        assertTrue(path.startsWith("research/"));
        String name = path.substring("research/".length());
        assertNotNull(BureauResearchStore.load(ctx, name));
    }
}
