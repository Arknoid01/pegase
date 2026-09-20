package com.pegasuscorp.orbe.chat;

/**
 * Taille de la dernière requête envoyée au modèle par rapport à son budget, et dernière
 * compaction du contexte : alimente la jauge « Contexte » du chat.
 */
public final class PromptStats {

    private static volatile int lastChars;
    private static volatile int maxChars;
    private static volatile long compactedAtMs;

    private PromptStats() {}

    static void note(int chars, PromptBudget.Level level) {
        lastChars = chars;
        maxChars = AgenticContextCompactor.maxPromptChars(level);
    }

    static void noteCompaction() {
        compactedAtMs = System.currentTimeMillis();
    }

    public static boolean hasData() {
        return maxChars > 0;
    }

    /** Part du budget utilisée par la dernière requête (0-100+). */
    public static int percent() {
        return maxChars > 0 ? (int) Math.round(100.0 * lastChars / maxChars) : 0;
    }

    public static boolean compactedRecently(long withinMs) {
        return compactedAtMs > 0 && System.currentTimeMillis() - compactedAtMs <= withinMs;
    }

    /** « Contexte 42 % » (+ « · abrégé » si la compaction a servi récemment). */
    public static String formatGauge(int percent, boolean compacted) {
        return "Contexte " + percent + " %" + (compacted ? " · anciens résultats abrégés" : "");
    }

    /** Visible tests. */
    static void resetForTest() {
        lastChars = 0;
        maxChars = 0;
        compactedAtMs = 0;
    }
}
