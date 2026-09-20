package com.pegasuscorp.orbe.termux;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;
import android.widget.Toast;

/** Lance une commande dans Termux via son service RUN_COMMAND (pas besoin de Termux:Tasker). */
public final class TermuxLauncher {

    private static final String TAG = "TermuxLauncher";
    private static final String TERMUX_PACKAGE = "com.termux";
    private static final String RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService";
    private static final String ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND";
    private static final String BASH_PATH = "/data/data/com.termux/files/usr/bin/bash";
    private static final String PROOT_DISTRO_PATH =
            "/data/data/com.termux/files/usr/bin/proot-distro";

    private TermuxLauncher() {}

    public static boolean isTermuxInstalled(Context ctx) {
        try {
            ctx.getPackageManager().getPackageInfo(TERMUX_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Lance `command` tel quel dans une session Termux interactive (base, hors proot). */
    public static void runInteractive(Context ctx, String command) {
        launch(ctx, BASH_PATH, new String[]{"-c", command});
    }

    /**
     * Lance `command` DANS le proot Ubuntu (même environnement que le double-tap
     * orbe « proot-distro login ubuntu ») — pas sur le Termux de base, pour que les
     * outils installés dans le proot (ex: claude) soient trouvés.
     */
    public static void runInProotUbuntu(Context ctx, String command) {
        launch(ctx, PROOT_DISTRO_PATH,
                new String[]{"login", "ubuntu", "--", "bash", "-lc", command});
    }

    private static void launch(Context ctx, String executablePath, String[] arguments) {
        if (!isTermuxInstalled(ctx)) {
            Toast.makeText(ctx, "Termux n'est pas installé", Toast.LENGTH_LONG).show();
            return;
        }
        Intent intent = new Intent(ACTION_RUN_COMMAND);
        intent.setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE);
        intent.putExtra("com.termux.RUN_COMMAND_PATH", executablePath);
        intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arguments);
        intent.putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home");
        intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", false);
        intent.putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0");
        try {
            // Android 8+ : le processus appelant peut être considéré "background" au
            // moment du geste (ex: callback système) — startService() est alors rejeté
            // avec IllegalStateException("Not allowed to start service Intent...").
            // startForegroundService() est autorisé dans ce cas ; Termux passe lui-même
            // son RunCommandService en foreground dès qu'il démarre.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent);
            } else {
                ctx.startService(intent);
            }
        } catch (Exception e) {
            Log.w(TAG, "launch", e);
            Toast.makeText(ctx, "Impossible de lancer Termux : " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }
}
