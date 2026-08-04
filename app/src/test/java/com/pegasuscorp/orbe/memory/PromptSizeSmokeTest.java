package com.pegasuscorp.orbe.memory;

import android.content.Context;

import com.pegasuscorp.orbe.chat.PromptBudget;
import com.pegasuscorp.orbe.session.Channel;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import static org.junit.Assert.*;

/**
 * Smoke : taille system après filtres mémoire / budget Groq TIGHT.
 * Affiche les chars dans la sortie test (rapport console).
 */
@RunWith(RobolectricTestRunner.class)
public class PromptSizeSmokeTest {

    private Context ctx;

    @Before
    public void setUp() {
        MemoryRepository.setAutoMigrateForTests(false);
        MemoryRepository.resetInstanceForTests();
        ctx = RuntimeEnvironment.getApplication();
    }

    @Test
    public void tightSystem_underGroqBudget() {
        String msg = "ouvre cursor et clique sur le micro";
        String normal = MemoryPromptBuilder.buildFullSystem(ctx, msg, true, false,
                Channel.COPILOT, PromptBudget.Level.NORMAL);
        String tight = MemoryPromptBuilder.buildFullSystem(ctx, msg, true, false,
                Channel.COPILOT, PromptBudget.Level.TIGHT);
        String emergency = MemoryPromptBuilder.buildFullSystem(ctx, msg, true, false,
                Channel.COPILOT, PromptBudget.Level.EMERGENCY);

        System.out.println("PROMPT_SIZE normal_chars=" + normal.length()
                + " est_tokens=" + (normal.length() / 4));
        System.out.println("PROMPT_SIZE tight_chars=" + tight.length()
                + " est_tokens=" + (tight.length() / 4));
        System.out.println("PROMPT_SIZE emergency_chars=" + emergency.length()
                + " est_tokens=" + (emergency.length() / 4));

        assertTrue("TIGHT should be <= NORMAL", tight.length() <= normal.length());
        assertTrue("EMERGENCY should be <= TIGHT", emergency.length() <= tight.length());
        // System seul (sans history/user/tools JSON) doit rester largement sous 8k tokens.
        assertTrue("TIGHT system est_tokens < 5500", tight.length() / 4 < 5500);
    }
}
