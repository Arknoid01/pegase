package com.pegasuscorp.orbe.tools;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Process;
import android.provider.Settings;

import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.pegasuscorp.orbe.PegaseNotificationListenerService;
import com.pegasuscorp.orbe.copilot.AccessibilityAccess;

/**
 * Ce qu'il manque pour utiliser un outil (permission, accès spécial) et comment le régler.
 * {@code null} = l'outil est utilisable.
 */
public final class ToolRequirement {

    public final String message;
    /** Action de réglages Android à ouvrir ; null = fiche de l'application. */
    private final String settingsAction;

    private ToolRequirement(String message, String settingsAction) {
        this.message = message;
        this.settingsAction = settingsAction;
    }

    /** Intent qui ouvre le réglage à modifier. */
    public Intent fixIntent(Context ctx) {
        Intent intent = settingsAction != null
                ? new Intent(settingsAction)
                : new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", ctx.getPackageName(), null));
        return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    public static ToolRequirement permission(Context ctx, String permission, String message) {
        return ContextCompat.checkSelfPermission(ctx, permission)
                == PackageManager.PERMISSION_GRANTED ? null : new ToolRequirement(message, null);
    }

    /** Photos : READ_MEDIA_IMAGES (Android 13+) ou READ_EXTERNAL_STORAGE. */
    public static ToolRequirement photos(Context ctx) {
        String perm = android.os.Build.VERSION.SDK_INT >= 33
                ? android.Manifest.permission.READ_MEDIA_IMAGES
                : android.Manifest.permission.READ_EXTERNAL_STORAGE;
        return permission(ctx, perm,
                "Autorise l'accès aux photos dans les réglages Android.");
    }

    public static ToolRequirement contacts(Context ctx) {
        return permission(ctx, android.Manifest.permission.READ_CONTACTS,
                "Autorise l'accès aux contacts dans les réglages Android.");
    }

    public static ToolRequirement usageAccess(Context ctx) {
        AppOpsManager ops = (AppOpsManager) ctx.getSystemService(Context.APP_OPS_SERVICE);
        boolean ok = ops != null && ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(), ctx.getPackageName()) == AppOpsManager.MODE_ALLOWED;
        return ok ? null : new ToolRequirement(
                "Autorise l'accès aux données d'utilisation à Pégase (Accès spécial).",
                Settings.ACTION_USAGE_ACCESS_SETTINGS);
    }

    public static ToolRequirement notificationListener(Context ctx) {
        boolean ok = PegaseNotificationListenerService.getInstance() != null
                || NotificationManagerCompat.getEnabledListenerPackages(ctx)
                        .contains(ctx.getPackageName());
        return ok ? null : new ToolRequirement(
                "Autorise l'accès aux notifications à Pégase (Accès spécial).",
                Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
    }

    public static ToolRequirement accessibility(Context ctx) {
        return AccessibilityAccess.isEnabled(ctx) ? null : new ToolRequirement(
                "Active le service d'accessibilité de Pégase pour contrôler l'écran.",
                Settings.ACTION_ACCESSIBILITY_SETTINGS);
    }
}
