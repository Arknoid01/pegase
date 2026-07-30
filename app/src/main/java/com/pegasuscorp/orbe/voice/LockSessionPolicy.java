package com.pegasuscorp.orbe.voice;

import android.app.KeyguardManager;
import android.content.Context;

/**
 * Règles quand le téléphone est verrouillé : discussion seule, pas d'outils.
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

    public static boolean allowsTools(Context context, boolean lockedChatMode) {
        return !lockedChatMode && !isDeviceLocked(context);
    }
}
