package com.pegasuscorp.orbe.sync;

import android.content.Context;

/**
 * Point d'entrée sync bloc-notes — délègue à {@link DriveSyncEngine}.
 */
public final class SyncEngine {

    public static final long MIN_INTERVAL_MS = DriveSyncEngine.MIN_INTERVAL_MS;
    public static final long STARTUP_DELAY_MS = DriveSyncEngine.STARTUP_DELAY_MS;

    public static final class Result {
        public final boolean attempted;
        public final boolean success;
        public final int sentOps;
        public final int appliedOps;

        Result(boolean attempted, boolean success, int sentOps, int appliedOps) {
            this.attempted = attempted;
            this.success = success;
            this.sentOps = sentOps;
            this.appliedOps = appliedOps;
        }
    }

    private SyncEngine() {}

    public static void scheduleStartupSync(Context context) {
        DriveSyncEngine.scheduleStartupSync(context);
    }

    public static void scheduleIfDue(Context context) {
        DriveSyncEngine.scheduleIfDue(context);
    }

    public static Result runOnce(Context context) {
        DriveSyncEngine.Result r = DriveSyncEngine.runOnce(context);
        return new Result(r.attempted, r.success, r.exportedOps, r.appliedOps);
    }
}
