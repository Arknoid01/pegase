package com.pegasuscorp.orbe.session;

import android.content.Context;

/**
 * Feature flags bootstrap HOME lazy (PR1) — rollback via prefs sans rebuild.
 */
public final class HomeLoadPolicy {

    private static final String PREFS = "home_load_policy";
    private static final String KEY_LAZY_BOOTSTRAP = "lazy_bootstrap";
    private static final String KEY_PREFETCH_ON_HOME_RESUME = "prefetch_on_home_resume";
    private static final String KEY_WAKE_ON_HOME_STABLE = "wake_on_home_stable_only";
    private static final String KEY_IDLE_PREFETCH_JOB = "idle_prefetch_job";
    private static final String KEY_DEFER_LOCATION = "defer_location_at_boot";

    private HomeLoadPolicy() {}

    public static boolean isLazyBootstrapEnabled(Context context) {
        if (context == null) return true;
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_LAZY_BOOTSTRAP, true);
    }

    public static void setLazyBootstrapEnabled(Context context, boolean enabled) {
        context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_LAZY_BOOTSTRAP, enabled)
                .apply();
    }

    /** Prefetch matinal via alarme ; pas au retour HOME si lazy ON. */
    public static boolean skipPrefetchOnHomeResume(Context context) {
        if (!isLazyBootstrapEnabled(context)) return false;
        return !context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_PREFETCH_ON_HOME_RESUME, false);
    }

    /** Wake word seulement après phase HOME STABLE (PR2). */
    public static boolean wakeOnHomeStableOnly(Context context) {
        if (!isLazyBootstrapEnabled(context)) return false;
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_WAKE_ON_HOME_STABLE, true);
    }

    /** Prefetch via JobScheduler charge + idle (PR4). */
    public static boolean useIdlePrefetchJob(Context context) {
        if (!isLazyBootstrapEnabled(context)) return false;
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_IDLE_PREFETCH_JOB, true);
    }

    /** Pas de dialog GPS au boot HOME (PR4). */
    public static boolean deferLocationAtBoot(Context context) {
        if (!isLazyBootstrapEnabled(context)) return false;
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_DEFER_LOCATION, true);
    }
}
