package com.pegasuscorp.orbe.tools.device;

import com.pegasuscorp.orbe.tools.ToolCallback;

import com.pegasuscorp.orbe.tools.ToolResult;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class TimerToolTest {

    @Before
    public void setUp() {
        TimerTool.resetForTests();
    }

    @After
    public void tearDown() {
        TimerTool.resetForTests();
    }

    @Test
    public void recentSimilarTimer_returnsMessageWithoutLaunch() throws Exception {
        TimerTool.seedActiveTimerForTests(300, System.currentTimeMillis());
        assertTrue(TimerTool.isRecentSimilarTimer(300));
        assertTrue(TimerTool.isRecentSimilarTimer(350)); // ±60s
        assertFalse(TimerTool.isRecentSimilarTimer(400));

        AtomicReference<String> reply = new AtomicReference<>();
        AtomicBoolean confirmAsked = new AtomicBoolean(false);
        AtomicBoolean exited = new AtomicBoolean(false);

        new TimerTool().execute(RuntimeEnvironment.getApplication(),
                new JSONObject().put("seconds", 300),
                new ToolCallback() {
                    @Override
                    public void onSuccess(ToolResult result) {
                        reply.set(result != null ? result.text : null);
                    }

                    @Override
                    public void onSuccessAndExit(ToolResult result) {
                        exited.set(true);
                    }

                    @Override
                    public void onConfirmNeeded(String question, Runnable onConfirm,
                            Runnable onCancel) {
                        confirmAsked.set(true);
                    }

                    @Override
                    public void onError(String error) {
                        fail(error);
                    }
                });

        assertFalse(confirmAsked.get());
        assertFalse(exited.get());
        assertNotNull(reply.get());
        assertTrue(reply.get().contains("tourne déjà") || reply.get().contains("tourne deja"));
        assertTrue(reply.get().contains("remplacer"));
        assertTrue(reply.get().contains("5 min"));
    }

    @Test
    public void confirmTrue_allowsReplace() throws Exception {
        TimerTool.seedActiveTimerForTests(300, System.currentTimeMillis());
        AtomicBoolean softBlock = new AtomicBoolean(false);
        new TimerTool().execute(RuntimeEnvironment.getApplication(),
                new JSONObject().put("seconds", 300).put("confirm", true),
                new ToolCallback() {
                    @Override
                    public void onSuccess(ToolResult result) {
                        if (result != null && result.text != null
                                && result.text.contains("tourne déjà")) {
                            softBlock.set(true);
                        }
                    }

                    @Override
                    public void onSuccessAndExit(ToolResult result) {}

                    @Override
                    public void onConfirmNeeded(String q, Runnable ok, Runnable no) {
                        fail("pas de confirm dialog");
                    }

                    @Override
                    public void onError(String error) {
                        // OK sans app minuteur sous Robolectric
                    }
                });
        assertFalse(softBlock.get());
    }

    @Test
    public void olderThanSixtySeconds_notBlocked() {
        TimerTool.seedActiveTimerForTests(300, System.currentTimeMillis() - 61_000L);
        assertFalse(TimerTool.isRecentSimilarTimer(300));
    }

    @Test
    public void formatMinutesLabel_rounds() {
        assertEquals("5 min", TimerTool.formatMinutesLabel(300));
        assertEquals("1 min", TimerTool.formatMinutesLabel(45));
    }
}
