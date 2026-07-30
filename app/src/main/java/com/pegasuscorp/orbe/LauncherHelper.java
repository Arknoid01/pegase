package com.pegasuscorp.orbe;

import android.app.Activity;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;

import androidx.annotation.Nullable;

/**
 * Aide à définir Orbe comme launcher / écran d'accueil par défaut.
 */
public final class LauncherHelper {

    private LauncherHelper() {}

    public static boolean isDefaultLauncher(Context context) {
        Intent home = new Intent(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        android.content.pm.ResolveInfo ri = context.getPackageManager()
                .resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        if (ri == null || ri.activityInfo == null) return false;
        return context.getPackageName().equals(ri.activityInfo.packageName);
    }

    /**
     * Ouvre le sélecteur système pour choisir l'écran d'accueil.
     * @return true si un intent a été lancé
     */
    public static boolean requestDefaultLauncher(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            RoleManager roleManager = activity.getSystemService(RoleManager.class);
            if (roleManager != null
                    && roleManager.isRoleAvailable(RoleManager.ROLE_HOME)
                    && !roleManager.isRoleHeld(RoleManager.ROLE_HOME)) {
                Intent intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME);
                activity.startActivity(intent);
                return true;
            }
        }

        Intent homeSettings = homeSettingsIntent();
        if (homeSettings != null && homeSettings.resolveActivity(activity.getPackageManager()) != null) {
            activity.startActivity(homeSettings);
            return true;
        }

        Intent home = new Intent(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        home.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity.startActivity(home);
        return true;
    }

    @Nullable
    private static Intent homeSettingsIntent() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS);
        }
        return null;
    }
}
