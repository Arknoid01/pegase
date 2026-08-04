package com.pegasuscorp.orbe.chat;

import com.pegasuscorp.orbe.contextstore.AttachedContextInjector;

/**
 * Budgets prompt provider-aware — shrink pré-appel (chemin nominal Groq).
 */
public final class PromptBudget {

    /** Cible tokens estimés (chars/4) pour Groq on_demand. */
    public static final int GROQ_MAX_PROMPT_TOKENS = 6_000;

    /** Skip retry 413 si le 1er essai a déjà dépassé ce délai. */
    public static final long GROQ_413_RETRY_MAX_FIRST_MS = 2_500L;

    public enum Level {
        /** Cerebras / OpenRouter — budgets larges existants. */
        NORMAL,
        /** Groq 1er essai — compresser avant POST. */
        TIGHT,
        /** Après 413 — encore plus agressif. */
        EMERGENCY
    }

    private PromptBudget() {}

    public static Level levelForProvider(String providerId) {
        if (LlmProvider.ID_GROQ.equals(providerId)) return Level.TIGHT;
        return Level.NORMAL;
    }

    public static int estimateTokens(int chars) {
        return Math.max(0, chars) / 4;
    }

    public static int historyRecentLimit(Level level) {
        if (level == Level.EMERGENCY) return 3;
        if (level == Level.TIGHT) return 4;
        return 6;
    }

    public static int attachedMaxChars(Level level) {
        if (level == Level.EMERGENCY) return 2_500;
        if (level == Level.TIGHT) return 6_000;
        return AttachedContextInjector.MAX_CHARS_CLOUD;
    }

    public static int personalityMaxChars(Level level) {
        if (level == Level.EMERGENCY) return 1_200;
        if (level == Level.TIGHT) return 2_000;
        return 6_000;
    }

    public static int screenMaxChars(Level level) {
        if (level == Level.EMERGENCY) return 800;
        if (level == Level.TIGHT) return 1_200;
        return 2_000;
    }

    public static boolean exceedsGroqBudget(int totalChars) {
        return estimateTokens(totalChars) > GROQ_MAX_PROMPT_TOKENS;
    }

    public static boolean isRequestTooLarge(Throwable e) {
        if (e == null) return false;
        String m = e.getMessage();
        if (m == null) return false;
        String lower = m.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("http 413")
                || lower.contains("request too large")
                || lower.contains("too large")
                || lower.contains("payload too large");
    }
}
