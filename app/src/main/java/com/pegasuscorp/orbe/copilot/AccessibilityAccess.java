package com.pegasuscorp.orbe.copilot;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.text.TextUtils;

/**
 * Vérifie et ouvre les réglages du service d'accessibilité Pégase.
 */
public final class AccessibilityAccess {

    private AccessibilityAccess() {}

    public static boolean isEnabled(Context context) {
        Context app = context.getApplicationContext();
        String pkg = app.getPackageName();
        String flat = Settings.Secure.getString(
                app.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(flat)) return false;
        String expected = new ComponentName(app, PegaseAccessibilityService.class)
                .flattenToString();
        for (String name : flat.split(":")) {
            if (expected.equals(name.trim())) return true;
        }
        return false;
    }

    public static void openSettings(Context context) {
        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }
}
