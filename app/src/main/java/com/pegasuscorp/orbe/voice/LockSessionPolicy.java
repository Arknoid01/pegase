package com.pegasuscorp.orbe.voice;

import android.app.KeyguardManager;
import android.content.Context;

/**
 * Règles quand le téléphone est verrouillé.
 * v3 P1 : liste blanche d'outils vocaux (calcul, minuteur, alarme, agenda vérifié).
 */
public final class LockSessionPolicy {

    public static final String LOCKED_LLM_HINT =
            "\n[Écran verrouillé : discussion uniquement, sans outil JSON ni action système]";

    public static final String UNLOCK_TOOL_MESSAGE =
            "Déverrouille ton téléphone pour que je puisse faire ça.";

    private LockSessionPolicy() {}

    public static boolean isDeviceLocked(Context context) {
        KeyguardManager km = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        return km != null && km.isKeyguardLocked();
    }

    /** Tous les outils (écran déverrouillé, session non verrouillée). */
    public static boolean allowsTools(Context context, boolean lockedChatMode) {
        if (!isDeviceLocked(context) && !lockedChatMode) return true;
        if (!isDeviceLocked(context)) return true;
        return false;
    }

    /** Outil direct autorisé (whitelist écran verrouillé). */
    public static boolean allowsTool(Context context, boolean lockedChatMode,
            String intentHint, String toolJson) {
        if (!isDeviceLocked(context) && !lockedChatMode) return true;
        if (!isDeviceLocked(context)) return true;
        return LockScreenToolPolicy.isWhitelistedOnLockScreen(intentHint, toolJson);
    }
}
