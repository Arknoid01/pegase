package com.pegasuscorp.orbe.memory;

import com.pegasuscorp.orbe.chat.ChatBackend;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.Arrays;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class SessionSummarizerTest {

    @Test
    public void fallbackSummary_usesFirstUserMessageAsTopic() {
        var ctx = RuntimeEnvironment.getApplication();
        var turns = Arrays.asList(
                new ChatBackend.Turn(true, "Parle-moi de mon projet Orbe"),
                new ChatBackend.Turn(false, "Bien sûr, dis-moi en plus.")
        );
        SessionSummary s = SessionSummarizer.fallbackSummary(ctx, turns);
        assertEquals("Parle-moi de mon projet Orbe", s.topic);
        assertTrue(s.summary.contains("Pégase"));
    }
}
