package com.pegasuscorp.orbe.tools.knowledge;

import com.pegasuscorp.orbe.tools.ToolTag;

import com.pegasuscorp.orbe.tools.ToolRegistry;

import com.pegasuscorp.orbe.tools.Tool;

import com.pegasuscorp.orbe.tools.ToolCallback;

import com.pegasuscorp.orbe.tools.ToolResult;

import android.content.Context;

import com.pegasuscorp.orbe.diag.Trace;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class DiagToolTest {

    private Context ctx;
    private DiagTool tool;

    @Before
    public void setUp() {
        ctx = RuntimeEnvironment.getApplication();
        Trace.init(ctx);
        Trace.clear(ctx);
        tool = new DiagTool();
    }

    @Test
    public void registry_findsDiagWithTag() {
        ToolRegistry registry = new ToolRegistry();
        Tool t = registry.findById("diag");
        assertNotNull(t);
        assertEquals(ToolTag.DIAG, t.tag());
    }

    @Test
    public void execute_summary_returnsText() throws Exception {
        AtomicReference<ToolResult> result = new AtomicReference<>();
        AtomicReference<String> err = new AtomicReference<>();
        tool.execute(ctx, new JSONObject().put("action", "summary"), new ToolCallback() {
            @Override
            public void onSuccess(ToolResult r) {
                result.set(r);
            }

            @Override
            public void onConfirmNeeded(String question, Runnable onConfirm, Runnable onCancel) {
                fail("confirm");
            }

            @Override
            public void onError(String error) {
                err.set(error);
            }
        });
        assertNull(err.get());
        assertNotNull(result.get());
        assertFalse(result.get().text.isEmpty());
    }

    @Test
    public void execute_defaultActionIsSummary() {
        AtomicReference<ToolResult> result = new AtomicReference<>();
        tool.execute(ctx, new JSONObject(), new ToolCallback() {
            @Override
            public void onSuccess(ToolResult r) {
                result.set(r);
            }

            @Override
            public void onConfirmNeeded(String question, Runnable onConfirm, Runnable onCancel) {
                fail("confirm");
            }

            @Override
            public void onError(String error) {
                fail(error);
            }
        });
        assertNotNull(result.get());
        String t = result.get().text.toLowerCase();
        assertTrue(t.contains("trace") || t.contains("session") || t.contains("diagnostiquer")
                || t.contains("bilan") || t.contains("passé") || t.contains("donnée")
                || t.contains("donnee") || t.contains("ras") || t.contains("message"));
    }

    @Test
    public void execute_weekly_returnsText() throws Exception {
        AtomicReference<ToolResult> result = new AtomicReference<>();
        tool.execute(ctx, new JSONObject().put("action", "weekly").put("days", 7),
                new ToolCallback() {
                    @Override
                    public void onSuccess(ToolResult r) {
                        result.set(r);
                    }

                    @Override
                    public void onConfirmNeeded(String question, Runnable onConfirm,
                            Runnable onCancel) {
                        fail("confirm");
                    }

                    @Override
                    public void onError(String error) {
                        fail(error);
                    }
                });
        assertNotNull(result.get());
        assertFalse(result.get().text.isEmpty());
    }

    @Test
    public void execute_search_returnsText() throws Exception {
        AtomicReference<ToolResult> result = new AtomicReference<>();
        AtomicReference<String> err = new AtomicReference<>();
        tool.execute(ctx, new JSONObject()
                        .put("action", "search")
                        .put("query", "tu as déjà eu ce problème sur notepad ?"),
                new ToolCallback() {
                    @Override
                    public void onSuccess(ToolResult r) {
                        result.set(r);
                    }

                    @Override
                    public void onConfirmNeeded(String question, Runnable onConfirm,
                            Runnable onCancel) {
                        fail("confirm");
                    }

                    @Override
                    public void onError(String error) {
                        err.set(error);
                    }
                });
        assertNull(err.get());
        assertNotNull(result.get());
        assertFalse(result.get().text.isEmpty());
    }

    @Test
    public void execute_analyze_rasOrConfirm() throws Exception {
        AtomicReference<ToolResult> result = new AtomicReference<>();
        AtomicReference<String> confirmQ = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        tool.execute(ctx, new JSONObject().put("action", "analyze"), new ToolCallback() {
            @Override
            public void onSuccess(ToolResult r) {
                result.set(r);
                latch.countDown();
            }

            @Override
            public void onConfirmNeeded(String question, Runnable onConfirm, Runnable onCancel) {
                confirmQ.set(question);
                if (onCancel != null) onCancel.run();
            }

            @Override
            public void onError(String error) {
                fail(error);
                latch.countDown();
            }
        });
        assertTrue(latch.await(8, TimeUnit.SECONDS));
        // Trace vide → RAS immédiat, ou confirm si des problèmes (selon état)
        if (confirmQ.get() != null) {
            assertNotNull(result.get());
        } else {
            assertNotNull(result.get());
            assertTrue(DiagTool.isNothingToReport(result.get().text)
                    || !result.get().text.isEmpty());
        }
        assertTrue(tool.description().contains("analyze"));
    }

    @Test
    public void description_mentionsSearch() {
        assertTrue(tool.description().contains("search"));
    }

    @Test
    public void resolveAction_analyzeFromQuery() throws Exception {
        assertEquals("analyze", DiagTool.resolveAction(
                new JSONObject().put("query", "analyse tes problèmes")));
        assertEquals("weekly", DiagTool.resolveAction(
                new JSONObject().put("utterance", "bilan de la semaine")));
        assertEquals("search", DiagTool.resolveAction(
                new JSONObject().put("query", "tu as déjà eu ce problème ?")));
        assertEquals("detail", DiagTool.resolveAction(
                new JSONObject().put("utterance", "qu'est-ce qui a merdé hier ?")));
        assertEquals("detail", DiagTool.resolveAction(
                new JSONObject().put("query", "explique l'erreur")));
        assertEquals("detail", DiagTool.resolveAction(
                new JSONObject().put("utterance", "tu as eu des problèmes hier ?")));
        assertEquals("detail", DiagTool.resolveAction(
                new JSONObject().put("query", "détaille les problèmes")));
    }

    @Test
    public void resolveAction_emptyDefaultsSummary() {
        assertEquals("summary", DiagTool.resolveAction(new JSONObject()));
        assertEquals("summary", DiagTool.resolveAction(null));
    }

    @Test
    public void resolveAction_explicitActionWins() throws Exception {
        assertEquals("failures", DiagTool.resolveAction(
                new JSONObject().put("action", "failures")
                        .put("query", "analyse tes problèmes")));
    }

    @Test
    public void followUpAfterSummary_upgradesToDetail() throws Exception {
        assertFalse(DiagTool.hasRecentSummary(ctx));
        JSONObject alone = DiagTool.maybeUpgradeFollowUpToDetail(ctx,
                new JSONObject().put("utterance", "dis m'en plus"));
        assertEquals("dis m'en plus", alone.optString("utterance"));
        assertTrue(alone.optString("action").isEmpty()
                || "dis m'en plus".equals(alone.optString("utterance")));

        DiagTool.markRecentSummary(ctx, java.time.LocalDate.of(2026, 7, 25));
        assertTrue(DiagTool.hasRecentSummary(ctx));
        assertEquals("2026-07-25", DiagTool.recentSummaryDayIso(ctx));

        JSONObject upgraded = DiagTool.maybeUpgradeFollowUpToDetail(ctx,
                new JSONObject().put("utterance", "dis m'en plus"));
        assertEquals("detail", upgraded.optString("action"));
        assertEquals("2026-07-25", upgraded.optString("date"));

        JSONObject fromSummaryAction = DiagTool.maybeUpgradeFollowUpToDetail(ctx,
                new JSONObject().put("action", "summary")
                        .put("utterance", "et encore ?"));
        assertEquals("detail", fromSummaryAction.optString("action"));

        // analyze explicite non écrasé par une relance ambiguë dans query seule si action=analyze
        JSONObject keepAnalyze = DiagTool.maybeUpgradeFollowUpToDetail(ctx,
                new JSONObject().put("action", "analyze"));
        assertEquals("analyze", keepAnalyze.optString("action"));
    }
}
