package com.pegasuscorp.orbe.sync;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.pegasuscorp.orbe.memory.MemoryRepository;
import com.pegasuscorp.orbe.notepad.NotepadStore;

import java.util.List;

/**
 * Sync bloc-notes et mémoires via Google Drive (SAF) — sans SSH ni API Drive.
 */
public final class DriveSyncEngine {

    public static final long MIN_INTERVAL_MS = 2L * 60_000L;
    public static final long STARTUP_DELAY_MS = 3_000L;
    public static final long DEBOUNCE_MS = 1_500L;

    public static final class Result {
        public final boolean attempted;
        public final boolean success;
        public final int exportedOps;
        public final int appliedOps;

        Result(boolean attempted, boolean success, int exportedOps, int appliedOps) {
            this.attempted = attempted;
            this.success = success;
            this.exportedOps = exportedOps;
            this.appliedOps = appliedOps;
        }

        static Result skipped() {
            return new Result(false, false, 0, 0);
        }
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Runnable debouncedSync;

    private DriveSyncEngine() {}

    public static void scheduleStartupSync(Context context) {
        Context app = context.getApplicationContext();
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(STARTUP_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            scheduleIfDue(app);
        }, "drive-sync-startup");
        t.setDaemon(true);
        t.start();
    }

    public static void scheduleAfterChange(Context context) {
        Context app = context.getApplicationContext();
        if (!DriveSyncPrefs.isConfigured(app)) return;
        if (debouncedSync != null) {
            MAIN.removeCallbacks(debouncedSync);
        }
        debouncedSync = () -> {
            Thread t = new Thread(() -> runOnce(app), "drive-sync-change");
            t.setDaemon(true);
            t.start();
        };
        MAIN.postDelayed(debouncedSync, DEBOUNCE_MS);
    }

    public static void scheduleIfDue(Context context) {
        Context app = context.getApplicationContext();
        if (!DriveSyncPrefs.isConfigured(app)) return;
        DriveSyncStateStore state = DriveSyncStateStore.get(app);
        long now = System.currentTimeMillis();
        if (now - state.getLastSyncAttemptMs() < MIN_INTERVAL_MS) return;
        state.setLastSyncAttemptMs(now);
        Thread t = new Thread(() -> runOnce(app), "drive-sync");
        t.setDaemon(true);
        t.start();
    }

    public static Result runOnce(Context context) {
        Context app = context.getApplicationContext();
        if (!DriveSyncPrefs.isConfigured(app)) return Result.skipped();

        DriveSyncStateStore state = DriveSyncStateStore.get(app);
        SyncJournal journal = SyncJournal.get(app);
        ExportResult export = exportPending(app, journal, state);
        int applied = importInbound(app, state);
        boolean upToDate = state.getLastExportedSeq() >= journal.latestCursor().lastSeq;
        boolean success = export.allOk && (export.count > 0 || applied > 0 || upToDate);
        state.setLastSyncResult(success);
        return new Result(true, success, export.count, applied);
    }

    private static final class ExportResult {
        final int count;
        final boolean allOk;

        ExportResult(int count, boolean allOk) {
            this.count = count;
            this.allOk = allOk;
        }
    }

    private static ExportResult exportPending(Context app, SyncJournal journal,
            DriveSyncStateStore state) {
        SyncCursor since = new SyncCursor(state.getLastExportedSeq(), "", 0);
        List<SyncOp> pending = journal.opsSince(since);
        int count = 0;
        boolean allOk = true;
        for (SyncOp op : pending) {
            if (DriveSyncStorage.writeOp(app, op)) {
                state.setLastExportedSeq(op.seq);
                count++;
            } else {
                allOk = false;
            }
        }
        if (pending.isEmpty()) allOk = true;
        return new ExportResult(count, allOk);
    }

    private static int importInbound(Context app, DriveSyncStateStore state) {
        List<SyncOp> inbound = DriveSyncStorage.listInboundPcOps(app);
        if (inbound.isEmpty()) return 0;
        NotepadStore store = NotepadStore.getInstance(app);
        MemoryRepository memory = MemoryRepository.getInstance(app);
        int applied = 0;
        for (SyncOp op : inbound) {
            if (state.isRemoteOpApplied(op.opId)) continue;
            boolean did = false;
            if (SyncOp.STORE_NOTEPAD.equals(op.store)) {
                did = NotepadSyncApplier.applyOne(store, op);
            } else if (SyncOp.STORE_MEMORY.equals(op.store)) {
                did = MemorySyncApplier.applyOne(memory, op);
            } else if (SyncOp.STORE_CONVERSATION.equals(op.store)) {
                did = ConversationSyncApplier.applyOne(memory, op);
            }
            state.markRemoteOpApplied(op.opId);
            if (did) applied++;
        }
        return applied;
    }
}
