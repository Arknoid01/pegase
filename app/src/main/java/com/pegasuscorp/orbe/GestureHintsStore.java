package com.pegasuscorp.orbe;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Fil d'Ariane gestes HOME — 3 whispers one-shot, puis plus jamais.
 */
public final class GestureHintsStore {

    public static final int HINT_DISCUSSION = 0;
    public static final int HINT_VOICE = 1;
    public static final int HINT_APPS = 2;

    private static final String PREFS = "gesture_hints";
    private static final String KEY_DONE = "gesture_thread_v1_done";
    private static final String KEY_SHOWN_MASK = "gesture_thread_v1_shown";
    private static final String KEY_DISCOVERED_MASK = "gesture_thread_v1_discovered";
    private static final String KEY_HOME_RETURNS = "gesture_thread_v1_home_returns";

    private static final String[] HINTS = {
            "Tape ici → discussion",
            "Appui long → voix",
            "Glisse vers le haut → apps"
    };

    private GestureHintsStore() {}

    public static boolean isComplete(Context ctx) {
        return prefs(ctx).getBoolean(KEY_DONE, false);
    }

    public static void reset(Context ctx) {
        prefs(ctx).edit().clear().apply();
    }

    /** Appelé à chaque onResume HOME tant que le fil n'est pas terminé. */
    public static void onHomeReturn(Context ctx) {
        if (isComplete(ctx)) return;
        SharedPreferences p = prefs(ctx);
        int n = p.getInt(KEY_HOME_RETURNS, 0) + 1;
        p.edit().putInt(KEY_HOME_RETURNS, n).apply();
    }

    /**
     * Prochain whisper à afficher, ou null si terminé / déjà tout montré /
     * trop de retours sans découverte.
     */
    public static String nextHint(Context ctx) {
        if (isComplete(ctx)) return null;
        SharedPreferences p = prefs(ctx);
        int shown = p.getInt(KEY_SHOWN_MASK, 0);
        int discovered = p.getInt(KEY_DISCOVERED_MASK, 0);
        // Tout découvert → fin
        if ((discovered & 0b111) == 0b111) {
            markDone(ctx);
            return null;
        }
        // Max 3 retours HOME pour les whispers
        if (p.getInt(KEY_HOME_RETURNS, 0) > 3 && shown != 0) {
            markDone(ctx);
            return null;
        }
        for (int i = 0; i < HINTS.length; i++) {
            int bit = 1 << i;
            if ((shown & bit) != 0) continue;
            if ((discovered & bit) != 0) continue;
            return HINTS[i];
        }
        // Tout montré une fois → fin
        if ((shown & 0b111) == 0b111) {
            markDone(ctx);
        }
        return null;
    }

    public static int indexOfHint(String hint) {
        if (hint == null) return -1;
        for (int i = 0; i < HINTS.length; i++) {
            if (HINTS[i].equals(hint)) return i;
        }
        return -1;
    }

    public static void markShown(Context ctx, int hintIndex) {
        if (hintIndex < 0 || hintIndex > 2) return;
        SharedPreferences p = prefs(ctx);
        int shown = p.getInt(KEY_SHOWN_MASK, 0) | (1 << hintIndex);
        p.edit().putInt(KEY_SHOWN_MASK, shown).apply();
        if ((shown & 0b111) == 0b111) markDone(ctx);
    }

    public static void markDiscovered(Context ctx, int hintIndex) {
        if (hintIndex < 0 || hintIndex > 2) return;
        SharedPreferences p = prefs(ctx);
        int discovered = p.getInt(KEY_DISCOVERED_MASK, 0) | (1 << hintIndex);
        p.edit().putInt(KEY_DISCOVERED_MASK, discovered).apply();
        if ((discovered & 0b111) == 0b111) markDone(ctx);
    }

    private static void markDone(Context ctx) {
        prefs(ctx).edit().putBoolean(KEY_DONE, true).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
