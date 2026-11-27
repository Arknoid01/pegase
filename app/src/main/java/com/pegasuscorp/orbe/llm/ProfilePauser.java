package com.pegasuscorp.orbe.llm;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;

/**
 * Met en pause le profil professionnel (« Clones Philia ») pendant que le modèle local est
 * chargé : ses processus (~200 Mo) sont arrêtés, ce qui laisse de la place au modèle sans que
 * Android tue les applis en arrière-plan. Reprise au déchargement du modèle.
 *
 * <p>Droit : lanceur par défaut au premier plan, ou permission MODIFY_QUIET_MODE accordée par
 * adb ({@code pm grant com.pegasuscorp.orbe android.permission.MODIFY_QUIET_MODE}). Si la reprise
 * est refusée (Orbe en arrière-plan), elle est retentée au prochain retour sur l'accueil.
 */
public final class ProfilePauser {

    private static final String TAG = "ProfilePauser";
    private static final String PREFS = "local_llm";
    private static final String KEY_ENABLED = "pause_profile_while_model";
    private static final String KEY_PAUSED_BY_US = "profile_paused_by_orbe";

    private ProfilePauser() {}

    public static boolean isEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_ENABLED, true);
    }

    public static void setEnabled(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, on).apply();
        if (!on) resumeIfPausedByUs(ctx);
    }

    /** Avant le chargement du modèle. Ne fait rien si Orbe n'est pas au premier plan. */
    public static void pauseForModel(Context ctx) {
        try {
            if (!isEnabled(ctx) || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;
            if (!orbeInForeground()) {
                Log.i(TAG, "Orbe pas au premier plan : profil laissé actif");
                return;
            }
            UserManager um = ctx.getSystemService(UserManager.class);
            UserHandle profile = managedProfile(um);
            if (profile == null || um.isQuietModeEnabled(profile)) return;
            boolean ok = um.requestQuietModeEnabled(true, profile);
            if (ok || um.isQuietModeEnabled(profile)) {
                prefs(ctx).edit().putBoolean(KEY_PAUSED_BY_US, true).apply();
                Log.i(TAG, "profil mis en pause pendant le modèle");
            }
        } catch (SecurityException e) {
            Log.w(TAG, "pause refusée : " + e.getMessage());
        } catch (Exception e) {
            Log.w(TAG, "pause impossible", e);
        }
    }

    /** Au déchargement du modèle, et à chaque retour sur l'accueil (reprise en attente). */
    public static void resumeIfPausedByUs(Context ctx) {
        try {
            if (!prefs(ctx).getBoolean(KEY_PAUSED_BY_US, false)) return;
            if (LocalLlmServer.modelLoaded) return;   // modèle encore en mémoire : on attend
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;
            UserManager um = ctx.getSystemService(UserManager.class);
            UserHandle profile = managedProfile(um);
            if (profile == null || !um.isQuietModeEnabled(profile)) {
                prefs(ctx).edit().putBoolean(KEY_PAUSED_BY_US, false).apply();
                return;
            }
            // Jamais d'écran de déverrouillage surprise : reprise seulement sans identifiant.
            um.requestQuietModeEnabled(false, profile,
                    UserManager.QUIET_MODE_DISABLE_ONLY_IF_CREDENTIAL_NOT_REQUIRED);
            if (!um.isQuietModeEnabled(profile)) {
                prefs(ctx).edit().putBoolean(KEY_PAUSED_BY_US, false).apply();
                Log.i(TAG, "profil repris");
            }
        } catch (SecurityException e) {
            Log.i(TAG, "reprise reportée au prochain accueil : " + e.getMessage());
        } catch (Exception e) {
            Log.w(TAG, "reprise impossible", e);
        }
    }

    private static UserHandle managedProfile(UserManager um) {
        UserHandle me = Process.myUserHandle();
        for (UserHandle u : um.getUserProfiles()) {
            if (!u.equals(me)) return u;   // Orbe tourne dans le profil principal
        }
        return null;
    }

    private static boolean orbeInForeground() {
        ActivityManager.RunningAppProcessInfo info = new ActivityManager.RunningAppProcessInfo();
        ActivityManager.getMyMemoryState(info);
        return info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
