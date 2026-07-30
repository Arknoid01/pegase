package com.pegasuscorp.orbe.voice;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Mode « Apprends à me connaître » — le routeur pose plus de questions et enrichit le corpus.
 */
public final class LearnModeStore {

    private static final String PREFS = "learn_mode";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_INTRO_SPOKEN = "intro_spoken";
    private static final String KEY_LAST_WEEKLY_REVIEW_MS = "last_weekly_review_ms";
    private static final long WEEK_MS = 7L * 24 * 60 * 60 * 1000;

    private LearnModeStore() {}

    public static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(Context context, boolean enabled) {
        SharedPreferences p = prefs(context);
        SharedPreferences.Editor e = p.edit().putBoolean(KEY_ENABLED, enabled);
        if (!enabled) {
            e.putBoolean(KEY_INTRO_SPOKEN, false);
        }
        e.apply();
    }

    public static boolean shouldSpeakIntro(Context context) {
        if (!isEnabled(context)) return false;
        return !prefs(context).getBoolean(KEY_INTRO_SPOKEN, false);
    }

    public static void markIntroSpoken(Context context) {
        prefs(context).edit().putBoolean(KEY_INTRO_SPOKEN, true).apply();
    }

    public static boolean shouldPromptWeeklyReview(Context context) {
        if (!isEnabled(context)) return false;
        long last = prefs(context).getLong(KEY_LAST_WEEKLY_REVIEW_MS, 0L);
        if (System.currentTimeMillis() - last < WEEK_MS) return false;
        return VoiceIntentLearnStore.getInstance(context).countLearnedThisWeek() > 0;
    }

    public static void markWeeklyReviewShown(Context context) {
        prefs(context).edit()
                .putLong(KEY_LAST_WEEKLY_REVIEW_MS, System.currentTimeMillis())
                .apply();
    }

    public static String introMessage() {
        return "J'apprends ta façon de parler. Je risque de te poser un peu plus de questions.";
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
