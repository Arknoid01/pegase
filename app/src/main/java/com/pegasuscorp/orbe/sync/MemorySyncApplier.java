package com.pegasuscorp.orbe.sync;

import com.pegasuscorp.orbe.memory.MemoryRepository;

import org.json.JSONObject;

import java.util.List;

/** Applique les ops distantes aux souvenirs permanents (LWW sur {@code tsMs}). */
public final class MemorySyncApplier {

    private MemorySyncApplier() {}

    public static int applyAll(MemoryRepository repo, List<SyncOp> ops) {
        if (repo == null || ops == null || ops.isEmpty()) return 0;
        int applied = 0;
        for (SyncOp op : ops) {
            if (applyOne(repo, op)) applied++;
        }
        return applied;
    }

    static boolean applyOne(MemoryRepository repo, SyncOp op) {
        if (op == null || !SyncOp.STORE_MEMORY.equals(op.store)) return false;
        if (SyncOp.OP_DELETE.equals(op.op)) {
            return repo.applyRemoteDelete(op.recordId, op.tsMs);
        }
        if (SyncOp.OP_UPSERT.equals(op.op) && op.payload != null) {
            return repo.applyRemoteUpsert(op.payload, op.tsMs);
        }
        return false;
    }
}
