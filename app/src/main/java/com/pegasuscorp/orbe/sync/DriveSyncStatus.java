package com.pegasuscorp.orbe.sync;

import android.content.Context;

/**
 * État affiché dans le bloc-notes (sync Drive).
 */
public final class DriveSyncStatus {

    public enum Level {
        NOT_CONFIGURED,
        PENDING,
        OK,
        ERROR
    }

    public final Level level;
    public final String label;

    DriveSyncStatus(Level level, String label) {
        this.level = level;
        this.label = label != null ? label : "";
    }

    public static DriveSyncStatus current(Context context) {
        Context app = context.getApplicationContext();
        if (!DriveSyncPrefs.isConfigured(app)) {
            return new DriveSyncStatus(Level.NOT_CONFIGURED, "Drive non configuré");
        }
        DriveSyncStateStore state = DriveSyncStateStore.get(app);
        SyncJournal journal = SyncJournal.get(app);
        long headSeq = journal.latestCursor().lastSeq;
        if (headSeq > state.getLastExportedSeq()) {
            return new DriveSyncStatus(Level.PENDING, "Sync en attente…");
        }
        if (!state.isLastSyncOk() && state.getLastSyncAtMs() > 0) {
            return new DriveSyncStatus(Level.ERROR, "Sync échouée");
        }
        if (state.getLastSyncAtMs() > 0) {
            return new DriveSyncStatus(Level.OK, "Sync OK");
        }
        return new DriveSyncStatus(Level.PENDING, "Sync en attente…");
    }
}
