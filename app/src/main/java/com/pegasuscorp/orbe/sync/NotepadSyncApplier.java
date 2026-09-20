package com.pegasuscorp.orbe.sync;

import com.pegasuscorp.orbe.notepad.NotepadStore;

import org.json.JSONObject;

import java.util.List;

/** Applique les ops distantes au bloc-notes (LWW sur {@code tsMs}). */
public final class NotepadSyncApplier {

    private NotepadSyncApplier() {}

    public static int applyAll(NotepadStore store, List<SyncOp> ops) {
        if (store == null || ops == null || ops.isEmpty()) return 0;
        int applied = 0;
        for (SyncOp op : ops) {
            if (applyOne(store, op)) applied++;
        }
        return applied;
    }

    static boolean applyOne(NotepadStore store, SyncOp op) {
        if (op == null || !SyncOp.STORE_NOTEPAD.equals(op.store)) return false;
        if (SyncOp.OP_DELETE.equals(op.op)) {
            return store.applyRemoteDelete(op.recordId, op.tsMs);
        }
        if (SyncOp.OP_UPSERT.equals(op.op) && op.payload != null) {
            return store.applyRemoteUpsert(op.payload, op.tsMs);
        }
        return false;
    }
}
