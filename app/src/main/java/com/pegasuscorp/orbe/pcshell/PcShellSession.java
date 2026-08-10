package com.pegasuscorp.orbe.pcshell;

import android.os.SystemClock;

/**
 * Session Terminal PC déverrouillée par biométrie — 30 min max, invalidée au passage en arrière-plan.
 */
public final class PcShellSession {

    public static final long SESSION_MS = 30L * 60L * 1000L;

    private static long unlockUntilElapsed;
    private static boolean lockedByBackground;

    private PcShellSession() {}

    public static synchronized void unlock() {
        unlockUntilElapsed = SystemClock.elapsedRealtime() + SESSION_MS;
        lockedByBackground = false;
    }

    public static synchronized void lockOnBackground() {
        lockedByBackground = true;
    }

    public static synchronized boolean isUnlocked() {
        if (lockedByBackground) return false;
        return unlockUntilElapsed > 0
                && SystemClock.elapsedRealtime() < unlockUntilElapsed;
    }

    /** Visible tests. */
    public static synchronized void resetForTests() {
        unlockUntilElapsed = 0L;
        lockedByBackground = false;
    }
}
