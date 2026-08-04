package com.pegasuscorp.orbe.copilot;

import com.pegasuscorp.orbe.tools.ToolCallback;
import com.pegasuscorp.orbe.tools.ToolResult;
import com.pegasuscorp.orbe.tools.copilot.UiActionTool;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class UiActionSequenceTest {

    @Test
    public void maxSequenceSteps_isReasonable() {
        assertTrue(A11yUiExecutor.MAX_SEQUENCE_STEPS >= 5);
        assertTrue(A11yUiExecutor.MAX_SEQUENCE_STEPS <= 8);
    }

    @Test
    public void runSequence_rejectsEmptySteps() throws Exception {
        AtomicReference<String> err = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        A11yUiExecutor.runSequence(RuntimeEnvironment.getApplication(),
                PegaseAccessibilityService.getInstance(),
                new JSONArray(),
                latchCb(null, err, done));
        assertTrue(done.await(2, TimeUnit.SECONDS));
        assertNotNull(err.get());
        assertTrue(err.get().toLowerCase().contains("vide")
                || err.get().toLowerCase().contains("séquence"));
    }

    @Test
    public void runSequence_rejectsTooManySteps() throws Exception {
        JSONArray steps = new JSONArray();
        for (int i = 0; i < A11yUiExecutor.MAX_SEQUENCE_STEPS + 1; i++) {
            steps.put(new JSONObject().put("action", "back"));
        }
        AtomicReference<String> err = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        A11yUiExecutor.runSequence(RuntimeEnvironment.getApplication(),
                PegaseAccessibilityService.getInstance(),
                steps,
                latchCb(null, err, done));
        assertTrue(done.await(2, TimeUnit.SECONDS));
        assertNotNull(err.get());
        assertTrue(err.get().contains("Trop") || err.get().contains("max"));
    }

    @Test
    public void uiActionTool_descriptionMentionsSteps() {
        String d = new UiActionTool().description();
        assertTrue(d.contains("steps"));
        assertTrue(d.contains("open") || d.contains("ouvre"));
        assertTrue(d.contains("JAMAIS") || d.toLowerCase().contains("jamais"));
        assertTrue(d.toLowerCase().contains("viewid") || d.contains("viewId"));
    }

    @Test
    public void uiActionTool_routesStepsArray() throws Exception {
        // Sans service a11y : requireService doit échouer clairement (pas de NPE).
        AtomicReference<String> err = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        JSONObject params = new JSONObject().put("steps", new JSONArray()
                .put(new JSONObject().put("action", "click").put("target", "Rechercher")));
        new UiActionTool().execute(RuntimeEnvironment.getApplication(), params,
                latchCb(null, err, done));
        assertTrue(done.await(3, TimeUnit.SECONDS));
        assertNotNull(err.get());
        assertFalse(err.get().isEmpty());
    }

    @Test
    public void waitForeground_nullArgs_ok() {
        assertNull(A11yUiExecutor.waitForeground(null, "com.android.chrome", 100));
        assertNull(A11yUiExecutor.waitForeground(
                PegaseAccessibilityService.getInstance(), "", 100));
    }

    private static ToolCallback latchCb(AtomicReference<ToolResult> ok,
            AtomicReference<String> err, CountDownLatch done) {
        return new ToolCallback() {
            @Override public void onSuccess(ToolResult result) {
                if (ok != null) ok.set(result);
                done.countDown();
            }

            @Override public void onError(String error) {
                err.set(error);
                done.countDown();
            }

            @Override
            public void onConfirmNeeded(String question, Runnable onConfirm, Runnable onCancel) {
                if (onCancel != null) onCancel.run();
            }
        };
    }
}
