package com.pegasuscorp.orbe.rag;

import android.content.Context;
import android.util.Log;

import com.pegasuscorp.orbe.memory.MemoryRepository;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Indexe les souvenirs JSON existants dans {@link VectorStore} (arrière-plan).
 */
public final class MemoryRagMigrator {

    private static final String TAG = "MemoryRagMigrator";
    private static final AtomicBoolean running = new AtomicBoolean(false);

    private MemoryRagMigrator() {}

    /** @return nombre de souvenirs nouvellement vectorisés */
    public static int migrate(Context ctx, MemoryRepository repo) {
        if (ctx == null || repo == null) return 0;
        if (!running.compareAndSet(false, true)) return 0;
        try {
            int indexed = repo.indexAllMissingNow();
            Log.i(TAG, "Migration RAG : " + indexed + " souvenirs nouvellement indexés");
            return indexed;
        } catch (Exception e) {
            Log.e(TAG, "Migration RAG impossible", e);
            return 0;
        } finally {
            running.set(false);
        }
    }

    public static void migrateAsync(Context ctx, MemoryRepository repo) {
        if (ctx == null || repo == null) return;
        Context app = ctx.getApplicationContext();
        new Thread(() -> migrate(app, repo), "rag-migrate").start();
    }
}
