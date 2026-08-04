package com.pegasuscorp.orbe.chat;

import org.junit.Test;

import static org.junit.Assert.*;

public class PromptBudgetTest {

    @Test
    public void groqLevel_isTight() {
        assertEquals(PromptBudget.Level.TIGHT,
                PromptBudget.levelForProvider(LlmProvider.ID_GROQ));
        assertEquals(PromptBudget.Level.NORMAL,
                PromptBudget.levelForProvider(LlmProvider.ID_CEREBRAS));
    }

    @Test
    public void emergency_shrinksHistoryAndAttached() {
        assertTrue(PromptBudget.historyRecentLimit(PromptBudget.Level.EMERGENCY)
                < PromptBudget.historyRecentLimit(PromptBudget.Level.NORMAL));
        assertTrue(PromptBudget.attachedMaxChars(PromptBudget.Level.TIGHT)
                < PromptBudget.attachedMaxChars(PromptBudget.Level.NORMAL));
    }

    @Test
    public void isRequestTooLarge_detects413() {
        assertTrue(PromptBudget.isRequestTooLarge(
                new RuntimeException("Groq HTTP 413 : Request too large")));
        assertFalse(PromptBudget.isRequestTooLarge(
                new RuntimeException("Groq HTTP 500 : oops")));
    }

    @Test
    public void exceedsGroqBudget_usesTokenEstimate() {
        assertTrue(PromptBudget.exceedsGroqBudget(PromptBudget.GROQ_MAX_PROMPT_TOKENS * 4 + 100));
        assertFalse(PromptBudget.exceedsGroqBudget(1000));
    }
}
