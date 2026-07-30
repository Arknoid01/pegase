package com.pegasuscorp.orbe.learning;

import android.content.Context;
import android.util.Log;

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
import java.util.List;

/**
 * Observations locales append-only ({@code files/learning/observations.jsonl}), rétention 90 j.
 */
public final class ObservationStore {

    private static final String TAG = "ObservationStore";
    private static final String FILE = "observations.jsonl";
    public static final long RETENTION_MS = 90L * 24L * 60L * 60L * 1000L;
    private static final int MAX_LINES = 5000;

    private static ObservationStore instance;
    private final File file;

    private ObservationStore(Context ctx) {
        File dir = PegaseFileSystem.get(ctx).dir("learning");
        file = new File(dir, FILE);
    }

    public static synchronized ObservationStore getInstance(Context ctx) {
        if (instance == null) {
            instance = new ObservationStore(ctx.getApplicationContext());
        }
        return instance;
    }

    public static synchronized void resetInstanceForTests() {
        instance = null;
    }

    public synchronized void append(Observation obs) {
        if (obs == null) return;
        try {
            if (!file.getParentFile().isDirectory()) {
                //noinspection ResultOfMethodCallIgnored
                file.getParentFile().mkdirs();
            }
            try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(file, true), StandardCharsets.UTF_8))) {
                w.write(obs.toJson().toString());
                w.newLine();
            }
            maybeTrim();
        } catch (Exception e) {
            Log.w(TAG, "append", e);
        }
    }

    public synchronized List<Observation> listSince(long sinceMs) {
        List<Observation> out = new ArrayList<>();
        if (!file.isFile()) return out;
        long cutoff = Math.max(0L, sinceMs);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                try {
                    Observation o = Observation.fromJson(new JSONObject(line));
                    if (o != null && o.atMs >= cutoff) out.add(o);
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            Log.w(TAG, "listSince", e);
        }
        return out;
    }

    public synchronized List<Observation> listOfType(String type, long sinceMs) {
        List<Observation> out = new ArrayList<>();
        for (Observation o : listSince(sinceMs)) {
            if (type != null && type.equals(o.type)) out.add(o);
        }
        return out;
    }

    /** Compte approximatif des lignes d'observation. */
    public synchronized int countAll() {
        return listSince(0L).size();
    }

    /** Tests. */
    public synchronized void clearAll() {
        if (file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    private void maybeTrim() {
        try {
            long cutoff = System.currentTimeMillis() - RETENTION_MS;
            List<String> keep = new ArrayList<>();
            if (!file.isFile()) return;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    new FileInputStream(file), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    try {
                        JSONObject o = new JSONObject(line);
                        if (o.optLong("atMs", 0L) >= cutoff) keep.add(line);
                    } catch (Exception ignored) {}
                }
            }
            if (keep.size() > MAX_LINES) {
                keep = keep.subList(keep.size() - MAX_LINES, keep.size());
            }
            File tmp = new File(file.getParentFile(), FILE + ".tmp");
            try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(tmp, false), StandardCharsets.UTF_8))) {
                for (String l : keep) {
                    w.write(l);
                    w.newLine();
                }
            }
            if (file.exists()) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(file);
        } catch (Exception e) {
            Log.w(TAG, "trim", e);
        }
    }
}
