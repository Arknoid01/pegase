package com.pegasuscorp.orbe.sync;

import com.pegasuscorp.orbe.diag.Trace;
import android.content.Context;

import com.pegasuscorp.orbe.fs.PegaseFileSystem;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/** État sync Drive — ops exportées / distantes déjà appliquées. */
public final class DriveSyncStateStore {

    private static DriveSyncStateStore instance;

    private final File stateFile;
    private long lastExportedSeq;
    private final Set<String> appliedRemoteOpIds = new HashSet<>();
    private long lastSyncAttemptMs;
    private long lastSyncAtMs;
    private boolean lastSyncOk = true;

    private DriveSyncStateStore(Context context) {
        stateFile = PegaseFileSystem.get(context.getApplicationContext()).driveSyncStateJson();
        load();
    }

    public static synchronized DriveSyncStateStore get(Context context) {
        if (instance == null) {
            instance = new DriveSyncStateStore(context.getApplicationContext());
        }
        return instance;
    }

    static synchronized void resetForTests(Context context) {
        instance = null;
        File f = PegaseFileSystem.get(context.getApplicationContext()).driveSyncStateJson();
        if (f.exists()) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    /** Recharge depuis le disque (tests persistance). */
    static synchronized void resetInstanceForTests() {
        instance = null;
    }

    public synchronized long getLastExportedSeq() {
        return lastExportedSeq;
    }

    public synchronized void setLastExportedSeq(long seq) {
        if (seq > lastExportedSeq) {
            lastExportedSeq = seq;
            save();
        }
    }

    public synchronized boolean isRemoteOpApplied(String opId) {
        return opId != null && appliedRemoteOpIds.contains(opId);
    }

    public synchronized void markRemoteOpApplied(String opId) {
        if (opId == null || opId.isEmpty()) return;
        appliedRemoteOpIds.add(opId);
        trimAppliedIds();
        save();
    }

    public synchronized long getLastSyncAttemptMs() {
        return lastSyncAttemptMs;
    }

    public synchronized void setLastSyncAttemptMs(long ts) {
        lastSyncAttemptMs = ts;
        save();
    }

    public synchronized long getLastSyncAtMs() {
        return lastSyncAtMs;
    }

    public synchronized boolean isLastSyncOk() {
        return lastSyncOk;
    }

    public synchronized void setLastSyncResult(boolean ok) {
        lastSyncOk = ok;
        lastSyncAtMs = System.currentTimeMillis();
        save();
    }

    private void trimAppliedIds() {
        if (appliedRemoteOpIds.size() <= 500) return;
        // Garder les plus récents — ordre d'insertion non garanti ; purge simple.
        int remove = appliedRemoteOpIds.size() - 400;
        var it = appliedRemoteOpIds.iterator();
        while (remove > 0 && it.hasNext()) {
            it.next();
            it.remove();
            remove--;
        }
    }

    private void load() {
        lastExportedSeq = 0;
        appliedRemoteOpIds.clear();
        lastSyncAttemptMs = 0;
        lastSyncAtMs = 0;
        lastSyncOk = true;
        if (!stateFile.isFile()) return;
        try {
            byte[] raw = readAll(stateFile);
            if (raw.length == 0) return;
            JSONObject o = new JSONObject(new String(raw, StandardCharsets.UTF_8));
            lastExportedSeq = o.optLong("lastExportedSeq", 0);
            lastSyncAttemptMs = o.optLong("lastSyncAttemptMs", 0);
            lastSyncAtMs = o.optLong("lastSyncAtMs", 0);
            lastSyncOk = o.optBoolean("lastSyncOk", true);
            JSONArray arr = o.optJSONArray("appliedRemoteOpIds");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    String id = arr.optString(i, "").trim();
                    if (!id.isEmpty()) appliedRemoteOpIds.add(id);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void save() {
        try {
            JSONObject o = new JSONObject();
            o.put("lastExportedSeq", lastExportedSeq);
            o.put("lastSyncAttemptMs", lastSyncAttemptMs);
            o.put("lastSyncAtMs", lastSyncAtMs);
            o.put("lastSyncOk", lastSyncOk);
            JSONArray arr = new JSONArray();
            for (String id : appliedRemoteOpIds) arr.put(id);
            o.put("appliedRemoteOpIds", arr);
            File parent = stateFile.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            try (FileOutputStream out = new FileOutputStream(stateFile)) {
                out.write(o.toString(2).getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            Trace.error("io_write", "DriveSyncStateStore.save : " + e.getClass().getSimpleName());
        }
    }

    private static byte[] readAll(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) Math.min(f.length(), 256_000)];
            int n = in.read(buf);
            if (n <= 0) return new byte[0];
            if (n == buf.length) return buf;
            byte[] out = new byte[n];
            System.arraycopy(buf, 0, out, 0, n);
            return out;
        }
    }
}
