package com.pegasuscorp.orbe.sync;

import android.content.Context;

import com.pegasuscorp.orbe.fs.PegaseFileSystem;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Journal append-only {@code sync/journal.jsonl}.
 */
public final class SyncJournal {

    private static SyncJournal instance;

    private final Context appContext;
    private final File journalFile;
    private long nextSeq = 1;

    private SyncJournal(Context context) {
        this.appContext = context.getApplicationContext();
        this.journalFile = PegaseFileSystem.get(appContext).syncJournalJsonl();
        loadNextSeq();
    }

    public static synchronized SyncJournal get(Context context) {
        if (instance == null) {
            instance = new SyncJournal(context.getApplicationContext());
        }
        return instance;
    }

    static synchronized void resetForTests(Context context) {
        instance = null;
        File f = PegaseFileSystem.get(context.getApplicationContext()).syncJournalJsonl();
        if (f.exists()) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    public synchronized SyncOp appendNotepadUpsert(String recordId, JSONObject payload) {
        return append(SyncOp.STORE_NOTEPAD, SyncOp.OP_UPSERT, recordId, payload);
    }

    public synchronized SyncOp appendNotepadDelete(String recordId) {
        return append(SyncOp.STORE_NOTEPAD, SyncOp.OP_DELETE, recordId, null);
    }

    public synchronized SyncOp appendMemoryUpsert(String recordId, JSONObject payload) {
        return append(SyncOp.STORE_MEMORY, SyncOp.OP_UPSERT, recordId, payload);
    }

    public synchronized SyncOp appendMemoryDelete(String recordId) {
        return append(SyncOp.STORE_MEMORY, SyncOp.OP_DELETE, recordId, null);
    }

    public synchronized SyncOp appendConversationUpsert(String recordId, JSONObject payload) {
        return append(SyncOp.STORE_CONVERSATION, SyncOp.OP_UPSERT, recordId, payload);
    }

    private SyncOp append(String store, String op, String recordId, JSONObject payload) {
        SyncDevice device = SyncDevice.get(appContext);
        long now = System.currentTimeMillis();
        SyncOp entry = new SyncOp(
                UUID.randomUUID().toString(),
                nextSeq++,
                now,
                device.deviceId,
                store,
                op,
                recordId,
                payload);
        writeLine(entry);
        return entry;
    }

    public synchronized List<SyncOp> opsSince(SyncCursor cursor) {
        SyncCursor c = cursor != null ? cursor : SyncCursor.empty();
        List<SyncOp> all = readAll();
        List<SyncOp> out = new ArrayList<>();
        for (SyncOp op : all) {
            if (op.seq > c.lastSeq) {
                out.add(op);
            } else if (op.seq == c.lastSeq && !c.lastOpId.isEmpty()
                    && !op.opId.equals(c.lastOpId)) {
                out.add(op);
            }
        }
        return out;
    }

    public synchronized SyncCursor latestCursor() {
        List<SyncOp> all = readAll();
        if (all.isEmpty()) return SyncCursor.empty();
        SyncOp last = all.get(all.size() - 1);
        return new SyncCursor(last.seq, last.opId, last.tsMs);
    }

    private void loadNextSeq() {
        long max = 0;
        for (SyncOp op : readAll()) {
            if (op.seq > max) max = op.seq;
        }
        nextSeq = max + 1;
    }

    private List<SyncOp> readAll() {
        if (!journalFile.isFile()) return Collections.emptyList();
        List<SyncOp> out = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(journalFile), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                try {
                    SyncOp op = SyncOp.fromJson(new JSONObject(line));
                    if (op != null) out.add(op);
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private void writeLine(SyncOp entry) {
        try {
            File parent = journalFile.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(journalFile, true), StandardCharsets.UTF_8))) {
                w.write(entry.toJson().toString());
                w.newLine();
            }
        } catch (Exception ignored) {
        }
    }
}
