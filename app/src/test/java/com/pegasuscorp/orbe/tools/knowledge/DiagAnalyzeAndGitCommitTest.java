package com.pegasuscorp.orbe.tools.knowledge;

import com.pegasuscorp.orbe.tools.orion.GitCommitTool;


import com.pegasuscorp.orbe.tools.ToolCallback;

import com.pegasuscorp.orbe.tools.ToolResult;

import android.content.Context;

import com.pegasuscorp.orbe.bureau.BureauSessionStore;
import com.pegasuscorp.orbe.chat.ApiKeyStore;
import com.pegasuscorp.orbe.diag.DiagSynthesizer;
import com.pegasuscorp.orbe.diag.Trace;
import com.pegasuscorp.orbe.voice.SpeechInputNormalizer;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class DiagAnalyzeAndGitCommitTest {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = RuntimeEnvironment.getApplication();
        Trace.init(ctx);
        Trace.clear(ctx);
        DiagTool.analyzeLlmOverride = null;
        ApiKeyStore.setGithubToken(ctx, "ghp_test");
        ApiKeyStore.setGithubRepo(ctx, "yanno/orbe");
        ApiKeyStore.setHostingerWebhook(ctx, "");
    }

    @After
    public void tearDown() {
        DiagTool.analyzeLlmOverride = null;
    }

    @Test
    public void analyze_ras_noLlmImmediate() throws Exception {
        AtomicBoolean llmCalled = new AtomicBoolean(false);
        AtomicReference<ToolResult> result = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        DiagTool.analyzeLlmOverride = (c, prompt) -> {
            llmCalled.set(true);
            return "should not run";
        };
        new DiagTool().execute(ctx, new JSONObject().put("action", "analyze"),
                new ToolCallback() {
                    @Override public void onSuccess(ToolResult r) {
                        result.set(r);
                        latch.countDown();
                    }
                    @Override public void onError(String error) {
                        latch.countDown();
                    }
                    @Override
                    public void onConfirmNeeded(String q, Runnable ok, Runnable no) {
                        fail("confirm not expected on RAS");
                    }
                });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertFalse(llmCalled.get());
        assertNotNull(result.get());
        assertTrue(DiagTool.isNothingToReport(result.get().text));
    }

    @Test
    public void analyze_withProblems_structuredMarkdownAndConfirm() throws Exception {
        Trace.toolHesitation("notepad", "ambiguous", "projet vs note", "ouvre le projet");
        Trace.toolFailureContext("weather", "http_400", "city missing", "meteo");

        String fakeMd = "## Problème : hésitation notepad\n"
                + "**Fichier concerné** : VoiceIntentRouter.java\n"
                + "**Cause** : ambiguïté projet/note\n"
                + "**Correction** : désambiguïser\n"
                + "**Prompt Cursor** :\n```\n// fix notepad routing\n```\n"
                + "Priorité : 🟡\n\n"
                + "## Problème : échec weather\n"
                + "**Fichier concerné** : WeatherTool.java\n"
                + "**Cause** : city missing\n"
                + "**Correction** : exiger la ville\n"
                + "**Prompt Cursor** :\n```\n// fix weather city\n```\n"
                + "Priorité : 🔴\n";

        DiagTool.analyzeLlmOverride = (c, prompt) -> {
            assertTrue(prompt.contains("[BILAN]"));
            assertTrue(prompt.contains("MAPPING FICHIERS ORBE"));
            assertTrue(prompt.contains("ConversationManager.java")
                    || prompt.contains("guardPhantom"));
            assertTrue(prompt.contains("AndroidManifest.xml"));
            assertTrue(prompt.contains("ingénieur QA") || prompt.contains("ingenieur QA")
                    || prompt.contains("QA"));
            assertTrue(prompt.contains("NE PAS générer de prompt Cursor")
                    || prompt.contains("pas de prompt"));
            return fakeMd;
        };

        AtomicReference<String> confirmQ = new AtomicReference<>();
        AtomicReference<ToolResult> afterYes = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        new DiagTool().execute(ctx, new JSONObject().put("action", "analyze"),
                new ToolCallback() {
                    @Override public void onSuccess(ToolResult r) {
                        afterYes.set(r);
                        latch.countDown();
                    }
                    @Override public void onError(String error) {
                        fail(error);
                        latch.countDown();
                    }
                    @Override
                    public void onConfirmNeeded(String q, Runnable ok, Runnable no) {
                        confirmQ.set(q);
                        assertTrue(q.contains("2") || q.toLowerCase().contains("problème")
                                || q.toLowerCase().contains("probleme"));
                        if (ok != null) ok.run();
                    }
                });
        assertTrue(latch.await(8, TimeUnit.SECONDS));
        assertNotNull(confirmQ.get());
        assertNotNull(afterYes.get());
        assertTrue(afterYes.get().llmContext.contains("## Problème"));
        assertTrue(afterYes.get().llmContext.contains("VoiceIntentRouter.java")
                || afterYes.get().llmContext.contains("WeatherTool.java"));
        assertTrue(afterYes.get().llmContext.contains("Prompt Cursor"));
        assertEquals(2, DiagTool.countProblems(afterYes.get().llmContext));
        String bureau = BureauSessionStore.loadToday(ctx);
        assertTrue(bureau.contains("Analyse Pégase"));
        assertTrue(bureau.contains("hésitation notepad") || bureau.contains("échec weather"));
    }

    @Test
    public void fallbackMarkdown_twoProblems() {
        String md = DiagSynthesizer.buildFallbackAnalyzeMarkdown(Arrays.asList(
                "bureau (repli) [medium] — LLM down",
                "action répétée [high] — notepad loop"));
        assertEquals(2, DiagTool.countProblems(md));
        assertTrue(md.contains("**Fichier concerné**"));
        assertTrue(md.contains("**Prompt Cursor**"));
        assertTrue(md.contains("Priorité"));
    }

    @Test
    public void buildAnalyzePrompt_includesFileMappingAndRules() {
        String prompt = DiagTool.buildAnalyzePrompt("2 hésitations notepad");
        assertTrue(prompt.contains("=== MAPPING FICHIERS ORBE ==="));
        assertTrue(prompt.contains("guardPhantom"));
        assertTrue(prompt.contains("NotepadTool.java"));
        assertTrue(prompt.contains("TimerTool.java"));
        assertTrue(prompt.contains("**Fichier concerné**"));
        assertTrue(prompt.contains("Prompt Cursor"));
        assertTrue(prompt.contains("[BILAN]2 hésitations notepad[/BILAN]"));
    }

    @Test
    public void fallbackMarkdown_mapsPhantomAndFilesPermission() {
        String md = DiagSynthesizer.buildFallbackAnalyzeMarkdown(Arrays.asList(
                "phantom_action [high] — j'ai noté sans outil",
                "tool_failure files permission [high] — stockage refusé",
                "phantom_action [high] — doublon scout")); // dedupé
        assertEquals(2, DiagTool.countProblems(md));
        assertTrue(md.contains("ConversationManager.java") || md.contains("guardPhantom"));
        assertTrue(md.contains("AndroidManifest.xml"));
        assertTrue(md.toLowerCase().contains("réglages")
                || md.toLowerCase().contains("reglages")
                || md.contains("Autorisations"));
    }

    @Test
    public void fallbackMarkdown_notepadUserFormulation_noCursorPrompt() {
        String md = DiagSynthesizer.buildFallbackAnalyzeMarkdown(Arrays.asList(
                "tool_failure notepad [medium] — introuvable / formulation utilisateur ambiguë"));
        assertEquals(1, DiagTool.countProblems(md));
        assertTrue(md.contains("pas de prompt") || md.contains("cause utilisateur"));
        assertTrue(md.contains("NotepadTool") || md.contains("NotepadEditor"));
    }

    @Test
    public void contextAnalyzer_analyzeTriggers() {
        String fold = SpeechInputNormalizer.fold("analyse tes problèmes")
                .replace('\'', ' ');
        assertTrue(com.pegasuscorp.orbe.memory.IntentDetector.looksLikeDiagAnalyze(fold));
        fold = SpeechInputNormalizer.fold("qu'est-ce qui ne va pas").replace('\'', ' ');
        assertTrue(com.pegasuscorp.orbe.memory.IntentDetector.looksLikeDiagAnalyze(fold));
        fold = SpeechInputNormalizer.fold("propose des corrections").replace('\'', ' ');
        assertTrue(com.pegasuscorp.orbe.memory.IntentDetector.looksLikeDiagAnalyze(fold));
    }

    @Test
    public void gitCommit_confirmShowsFileChangesMessage() throws Exception {
        AtomicReference<String> question = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        new GitCommitTool().execute(ctx, new JSONObject()
                        .put("repo", "yanno/orbe")
                        .put("path", "app/src/main/java/.../CalculatorTool.java")
                        .put("content", "class X {}")
                        .put("message", "fix(calculator): support notations françaises")
                        .put("changes", new JSONArray()
                                .put("Normalisation ÷ → /")
                                .put("Support virgule décimale")
                                .put("Support % comme /100")),
                new ToolCallback() {
                    @Override public void onSuccess(ToolResult result) {
                        latch.countDown();
                    }
                    @Override public void onError(String message) {
                        latch.countDown();
                    }
                    @Override
                    public void onConfirmNeeded(String q, Runnable ok, Runnable no) {
                        question.set(q);
                        if (no != null) no.run();
                    }
                });
        assertTrue(latch.await(3, TimeUnit.SECONDS));
        String q = question.get();
        assertNotNull(q);
        assertTrue(q.contains("📁"));
        assertTrue(q.contains("CalculatorTool.java"));
        assertTrue(q.contains("✏️"));
        assertTrue(q.contains("Normalisation") || q.contains("÷"));
        assertTrue(q.contains("virgule") || q.contains("%"));
        assertTrue(q.contains("💬"));
        assertTrue(q.contains("fix(calculator):"));
        assertTrue(q.contains("C'est bon ?"));
    }

    @Test
    public void gitCommit_buildConfirmQuestion_format() {
        String q = GitCommitTool.buildConfirmQuestion(
                "CalculatorTool.java",
                Arrays.asList("Normalisation ÷ → /", "Support virgule décimale",
                        "Support % comme /100"),
                "fix(calculator): support notations françaises",
                false,
                ctx);
        assertTrue(q.startsWith("Je vais committer"));
        assertTrue(q.contains("📁 CalculatorTool.java"));
        assertTrue(q.contains("- Normalisation"));
        assertTrue(q.contains("💬 fix(calculator):"));
    }
}
