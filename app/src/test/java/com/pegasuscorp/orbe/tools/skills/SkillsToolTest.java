package com.pegasuscorp.orbe.tools.skills;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import com.pegasuscorp.orbe.tools.ToolCallback;
import com.pegasuscorp.orbe.tools.ToolResult;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(RobolectricTestRunner.class)
public class SkillsToolTest {

    private static final class Capture implements ToolCallback {
        final AtomicReference<String> ok = new AtomicReference<>();
        final AtomicReference<String> error = new AtomicReference<>();

        @Override public void onSuccess(ToolResult result) { ok.set(result.text); }
        @Override public void onSuccessAndExit(ToolResult result) { ok.set(result.text); }
        @Override public void onError(String message) { error.set(message); }
        @Override public void onConfirmNeeded(String q, Runnable yes, Runnable no) {}
    }

    private static Capture run(Context ctx, JSONObject params) {
        Capture c = new Capture();
        new SkillsTool().execute(ctx, params, c);
        return c;
    }

    @Test
    public void listReadEtErreurs() throws Exception {
        Context ctx = RuntimeEnvironment.getApplication();
        File dir = new File(ctx.getFilesDir(), "skills/rapport");
        assertTrue(dir.mkdirs());
        Files.write(new File(dir, "SKILL.md").toPath(),
                "---\nname: Rapport\ndescription: Rapport du lundi\n---\n1. Lis l'agenda."
                        .getBytes(StandardCharsets.UTF_8));

        assertTrue(run(ctx, new JSONObject().put("action", "list")).ok.get()
                .contains("rapport : Rapport du lundi"));
        String body = run(ctx, new JSONObject().put("action", "read").put("id", "rapport"))
                .ok.get();
        assertNotNull(body);
        assertTrue(body.contains("1. Lis l'agenda."));

        Capture unknown = run(ctx, new JSONObject().put("action", "read").put("id", "nope"));
        assertTrue(unknown.error.get().startsWith("Skill inconnu"));
        assertEquals("Précise l'id du skill (voir skills list).",
                run(ctx, new JSONObject().put("action", "read")).error.get());
    }
}
