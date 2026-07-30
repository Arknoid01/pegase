package com.pegasuscorp.orbe.f1companion;

import android.content.Context;
import android.util.Log;

import com.pegasuscorp.orbe.contextstore.ContextualFileStore;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Persistance fiche week-end + miroir Markdown dans les contextes nommés « f1 ».
 */
public final class F1CompanionStore {

    private static final String TAG = "F1CompanionStore";
    public static final String CONTEXT_KEYWORD = "f1";
    public static final long STALE_MS = 6L * 60 * 60 * 1000;

    private F1CompanionStore() {}

    public static File weekendFile(Context ctx) {
        File dir = new File(ctx.getApplicationContext().getFilesDir(), "f1");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, "weekend.json");
    }

    public static WeekendSnapshot load(Context ctx) {
        File f = weekendFile(ctx);
        if (!f.isFile()) return null;
        try {
            byte[] raw = readAll(f);
            if (raw.length == 0) return null;
            return WeekendSnapshot.fromJson(new JSONObject(new String(raw, StandardCharsets.UTF_8)));
        } catch (Exception e) {
            Log.w(TAG, "load", e);
            return null;
        }
    }

    public static void save(Context ctx, WeekendSnapshot snap) {
        if (ctx == null || snap == null) return;
        try {
            snap.updatedAtMs = System.currentTimeMillis();
            writeAll(weekendFile(ctx), snap.toJson().toString(2).getBytes(StandardCharsets.UTF_8));
            // Miroir disque pour chargement à la demande — ne pas coller en session
            // (sinon « F1 » reste affiché en Discussion en permanence).
            ContextualFileStore store = ContextualFileStore.getInstance(ctx);
            store.save(CONTEXT_KEYWORD, snap.toMarkdown());
        } catch (Exception e) {
            Log.w(TAG, "save", e);
        }
    }

    public static boolean isStale(WeekendSnapshot snap) {
        if (snap == null || snap.updatedAtMs <= 0) return true;
        return System.currentTimeMillis() - snap.updatedAtMs > STALE_MS;
    }

    /**
     * Charge la fiche locale, ou rafraîchit via OpenF1 si absente / périmée / force.
     */
    public static WeekendSnapshot ensureFresh(Context ctx, boolean forceRefresh) throws Exception {
        WeekendSnapshot local = load(ctx);
        if (!forceRefresh && local != null && local.hasRaceResults() && !isStale(local)) {
            return local;
        }
        WeekendSnapshot fresh = OpenF1Service.fetchLatestRaceWeekend();
        save(ctx, fresh);
        try {
            F1MemoryStore.resolveAgainstRace(ctx, fresh);
            F1MemoryStore.mirrorContext(ctx);
        } catch (Exception e) {
            Log.w(TAG, "resolve memory", e);
        }
        return fresh;
    }

    private static byte[] readAll(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) Math.min(f.length(), 2_000_000)];
            int n = in.read(buf);
            if (n <= 0) return new byte[0];
            if (n == buf.length) return buf;
            byte[] out = new byte[n];
            System.arraycopy(buf, 0, out, 0, n);
            return out;
        }
    }

    private static void writeAll(File f, byte[] data) throws Exception {
        File parent = f.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(data);
        }
    }
}
