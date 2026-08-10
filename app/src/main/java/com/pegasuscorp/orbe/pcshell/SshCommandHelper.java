package com.pegasuscorp.orbe.pcshell;

import android.text.TextUtils;

/**
 * Timeout et encapsulation distante pour {@link SshConnectionManager}.
 */
public final class SshCommandHelper {

    private SshCommandHelper() {}

    public static long resolveTimeoutMs(String command, Integer savedTimeoutSec) {
        int sec = resolveTimeoutSec(command, savedTimeoutSec);
        return sec * 1000L;
    }

    public static int resolveTimeoutSec(String command, Integer savedTimeoutSec) {
        if (savedTimeoutSec != null && savedTimeoutSec > 0) {
            return Math.min(savedTimeoutSec, PcShellPrefs.MAX_TIMEOUT_SEC);
        }
        if (looksLikeLongPackageCommand(command)) {
            return PcShellPrefs.PACMAN_TIMEOUT_SEC;
        }
        return PcShellPrefs.DEFAULT_TIMEOUT_SEC;
    }

    /** Commandes longues : session détachée (setsid) sans PTY pour limiter SIGHUP si coupure. */
    public static boolean useDetachedSession(String command, long timeoutMs) {
        return timeoutMs > PcShellPrefs.DEFAULT_TIMEOUT_SEC * 1000L
                || looksLikeLongPackageCommand(command);
    }

    public static boolean looksLikeLongPackageCommand(String command) {
        if (TextUtils.isEmpty(command)) return false;
        String lower = command.toLowerCase();
        return lower.contains("pacman")
                || lower.contains("yay ")
                || lower.contains("paru ")
                || lower.contains("dnf ")
                || lower.contains("apt-get ")
                || lower.contains("apt install");
    }

    /**
     * Nouvelle session/process group côté serveur — pacman survit mieux à une fermeture SSH brusque.
     */
    public static String wrapDetached(String command) {
        if (TextUtils.isEmpty(command)) return command;
        return "setsid bash -lc '" + shellQuote(command) + "' </dev/null";
    }

    static String shellQuote(String command) {
        return command.replace("'", "'\\''");
    }
}
