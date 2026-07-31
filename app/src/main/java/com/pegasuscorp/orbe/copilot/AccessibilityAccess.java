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
        if (PegaseAccessibilityService.getInstance() != null) return true;
        Context app = context.getApplicationContext();
        String flat = Settings.Secure.getString(
                app.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(flat)) return false;
        ComponentName expected = new ComponentName(app, PegaseAccessibilityService.class);
        String full = expected.flattenToString();
        String shortForm = expected.flattenToShortString();
        for (String name : flat.split(":")) {
            String n = name.trim();
            if (n.isEmpty()) continue;
            if (full.equals(n) || shortForm.equals(n)) return true;
            ComponentName cn = ComponentName.unflattenFromString(n);
            if (cn != null && sameService(expected, cn)) return true;
        }
        return false;
    }

    /** Accepte forme courte {@code pkg/.Class} et forme longue {@code pkg/pkg.Class}. */
    static boolean sameService(ComponentName expected, ComponentName actual) {
        if (expected == null || actual == null) return false;
        if (!expected.getPackageName().equals(actual.getPackageName())) return false;
        String a = expected.getClassName();
        String b = actual.getClassName();
        if (a.equals(b)) return true;
        if (b.startsWith(".")) {
            return a.equals(actual.getPackageName() + b);
        }
        if (a.startsWith(".")) {
            return b.equals(expected.getPackageName() + a);
        }
        return false;
    }

    public static void openSettings(Context context) {
        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }
}
