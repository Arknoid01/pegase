package com.pegasuscorp.orbe.tools.orion;

import com.pegasuscorp.orbe.tools.ToolRegistry;

import com.pegasuscorp.orbe.tools.ToolCallback;

import com.pegasuscorp.orbe.tools.ToolResult;

import android.content.Context;

import com.pegasuscorp.orbe.chat.ApiKeyStore;
import com.pegasuscorp.orbe.orion.GeneratedFiles;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class GitCommitToolTest {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = RuntimeEnvironment.getApplication();
        ApiKeyStore.setGithubToken(ctx, "");
        ApiKeyStore.setGithubRepo(ctx, "");
        File dir = GeneratedFiles.dir(ctx);
        File[] old = dir.listFiles();
        if (old != null) {
            for (File f : old) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    @Test
    public void commitWithoutToken_errors() throws Exception {
        AtomicReference<String> err = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        new GitCommitTool().execute(ctx, new JSONObject()
                        .put("path", "a.md")
                        .put("content", "hi")
                        .put("confirm", true),
                new ToolCallback() {
                    @Override public void onSuccess(ToolResult result) {
                        latch.countDown();
                    }
                    @Override public void onError(String message) {
                        err.set(message);
                        latch.countDown();
                    }
                    @Override
                    public void onConfirmNeeded(String q, Runnable ok, Runnable no) {
                        if (ok != null) ok.run();
                    }
                });
        assertTrue(latch.await(3, TimeUnit.SECONDS));
        assertNotNull(err.get());
        assertTrue(err.get().toLowerCase().contains("token")
                || err.get().toLowerCase().contains("github"));
    }

    @Test
    public void commitAsksConfirmationByDefault() throws Exception {
        ApiKeyStore.setGithubToken(ctx, "ghp_test");
        AtomicBoolean asked = new AtomicBoolean(false);
        AtomicReference<String> cancelMsg = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        new GitCommitTool().execute(ctx, new JSONObject()
                        .put("path", "README.md")
                        .put("content", "# Orbe")
                        .put("message", "test")
                        .put("repo", "yanno/orbe"),
                new ToolCallback() {
                    @Override public void onSuccess(ToolResult result) {
                        cancelMsg.set(result != null ? result.text : null);
                        latch.countDown();
                    }
                    @Override public void onError(String message) {
                        latch.countDown();
                    }
                    @Override
                    public void onConfirmNeeded(String q, Runnable ok, Runnable no) {
                        asked.set(true);
                        assertTrue(q.contains("README.md") || q.contains("📁"));
                        assertTrue(q.contains("yanno/orbe") || q.contains("C'est bon"));
                        if (no != null) no.run();
                    }
                });
        assertTrue(latch.await(3, TimeUnit.SECONDS));
        assertTrue(asked.get());
        assertNotNull(cancelMsg.get());
        assertTrue(cancelMsg.get().toLowerCase().contains("annul"));
    }

    @Test
    public void localFile_loadsContentAndConfirms() throws Exception {
        ApiKeyStore.setGithubToken(ctx, "ghp_test");
        GeneratedFiles.save(ctx, "push_me.java", "class PushMe {}");
        AtomicBoolean asked = new AtomicBoolean(false);
        AtomicReference<String> question = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        new GitCommitTool().execute(ctx, new JSONObject()
                        .put("local_file", "push_me.java")
                        .put("repo", "yanno/orbe")
                        .put("message", "feat: push_me"),
                new ToolCallback() {
                    @Override public void onSuccess(ToolResult result) {
                        latch.countDown();
                    }
                    @Override public void onError(String message) {
                        latch.countDown();
                    }
                    @Override
                    public void onConfirmNeeded(String q, Runnable ok, Runnable no) {
                        asked.set(true);
                        question.set(q);
                        if (no != null) no.run();
                    }
                });
        assertTrue(latch.await(3, TimeUnit.SECONDS));
        assertTrue(asked.get());
        assertNotNull(question.get());
        assertTrue(question.get().contains("push_me.java"));
    }

    @Test
    public void withoutRepo_offersChoice() throws Exception {
        ApiKeyStore.setGithubToken(ctx, "ghp_test");
        ApiKeyStore.setGithubRepo(ctx, "yanno/default-repo");
        AtomicBoolean choiceAsked = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);
        new GitCommitTool().execute(ctx, new JSONObject()
                        .put("path", "x.md")
                        .put("content", "hi"),
                new ToolCallback() {
                    @Override public void onSuccess(ToolResult result) {
                        latch.countDown();
                    }
                    @Override public void onError(String message) {
                        latch.countDown();
                    }
                    @Override
                    public void onConfirmNeeded(String q, Runnable ok, Runnable no) {
                        latch.countDown();
                    }
                    @Override
                    public void onChoiceNeeded(String title, String[] labels,
                            IntConsumer onChosen, Runnable onCancel) {
                        choiceAsked.set(true);
                        assertNotNull(labels);
                        assertTrue(labels.length >= 2);
                        boolean hasCreate = false;
                        for (String l : labels) {
                            if (l != null && l.contains("Créer")) hasCreate = true;
                        }
                        assertTrue(hasCreate);
                        if (onCancel != null) onCancel.run();
                    }
                });
        assertTrue(latch.await(15, TimeUnit.SECONDS));
        assertTrue(choiceAsked.get());
    }

    @Test
    public void registryContainsGitCommit() {
        assertNotNull(new ToolRegistry().findById("git_commit"));
    }
}
