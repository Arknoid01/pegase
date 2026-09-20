package com.pegasuscorp.orbe.pcshell;

import android.content.Context;
import android.text.TextUtils;

/**
 * Slug de commande en attente — exécutée après déverrouillage biométrique (ntfy, deep link).
 */
public final class PcShellAutoRunPending {

    public static final String EXTRA_AUTO_RUN_COMMAND = "autoRunCommand";
    /** Slug intégré pour {@code sudo pacman -Syu} via notification ntfy. */
    public static final String SLUG_MISE_A_JOUR_ARCH = "mise-a-jour-arch";

    private static final String PREFS = "pc_shell_autorun";
    private static final String KEY_SLUG = "slug";

    private static String pendingSlug;

    private PcShellAutoRunPending() {}

    public static synchronized void setAfterUnlock(Context context, String slug) {
        if (TextUtils.isEmpty(slug)) {
            pendingSlug = null;
            clearPersisted(context);
            return;
        }
        pendingSlug = slug.trim();
        persist(context, pendingSlug);
    }

    public static synchronized String takePendingSlug(Context context) {
        String slug = pendingSlug;
        pendingSlug = null;
        if (context != null) {
            Context app = context.getApplicationContext();
            String stored = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_SLUG, "");
            if (TextUtils.isEmpty(slug) && !TextUtils.isEmpty(stored)) {
                slug = stored;
            }
            clearPersisted(context);
        }
        return TextUtils.isEmpty(slug) ? null : slug;
    }

    public static synchronized void resetForTests() {
        pendingSlug = null;
    }

    private static void persist(Context context, String slug) {
        if (context == null) return;
        context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_SLUG, slug)
                .apply();
    }

    private static void clearPersisted(Context context) {
        if (context == null) return;
        context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_SLUG)
                .apply();
    }
}
