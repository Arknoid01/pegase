package com.pegasuscorp.orbe.learning;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.pegasuscorp.orbe.fs.PegaseFileSystem;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Candidats d'apprentissage locaux ({@code files/learning/candidates.json}).
 */
public final class LearningCandidateStore {

    private static final String TAG = "LearningCandidateStore";
    private static final String FILE = "candidates.json";

    private static LearningCandidateStore instance;
    private final File file;
    private List<LearningCandidate> cache;

    private LearningCandidateStore(Context ctx) {
        File dir = PegaseFileSystem.get(ctx).dir("learning");
        file = new File(dir, FILE);
        cache = load();
    }

    public static synchronized LearningCandidateStore getInstance(Context ctx) {
        if (instance == null) {
            instance = new LearningCandidateStore(ctx.getApplicationContext());
        }
        return instance;
    }

    public static synchronized void resetInstanceForTests() {
        instance = null;
    }

    public synchronized List<LearningCandidate> listAll() {
        return new ArrayList<>(cache);
    }

    public synchronized List<LearningCandidate> listPending() {
        List<LearningCandidate> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (LearningCandidate c : cache) {
            if (c.isPending() && c.quietUntilMs <= now) out.add(c);
        }
        return out;
    }

    public synchronized LearningCandidate get(String id) {
        if (TextUtils.isEmpty(id)) return null;
        for (LearningCandidate c : cache) {
            if (id.equals(c.id)) return c;
        }
        return null;
    }

    /**
     * Upsert PENDING by fingerprint ; ignore if recently refused/snoozed same fingerprint.
     * @return stored candidate or null if blocked
     */
    public synchronized LearningCandidate upsertPending(LearningCandidate incoming) {
        if (incoming == null) return null;
        long now = System.currentTimeMillis();
        String fp = incoming.fingerprint();
        for (LearningCandidate c : cache) {
            if (!fp.equals(c.fingerprint())) continue;
            if (LearningCandidate.STATUS_REFUSED.equals(c.status)
                    || LearningCandidate.STATUS_SNOOZED.equals(c.status)) {
                if (c.quietUntilMs > now) return null;
            }
            if (c.isPending()) {
                LearningCandidate updated = new LearningCandidate(
                        c.id, incoming.type, incoming.targetId, incoming.proposal,
                        incoming.evidenceCount, incoming.confidence, incoming.reason,
                        LearningCandidate.STATUS_PENDING, now, c.createdAtMs, 0L);
                replace(c.id, updated);
                return updated;
            }
        }
        LearningCandidate created = new LearningCandidate(
                TextUtils.isEmpty(incoming.id) ? UUID.randomUUID().toString() : incoming.id,
                incoming.type, incoming.targetId, incoming.proposal,
                incoming.evidenceCount, incoming.confidence, incoming.reason,
                LearningCandidate.STATUS_PENDING, now, now, 0L);
        cache.add(created);
        save();
        return created;
    }

    public synchronized void replace(String id, LearningCandidate next) {
        for (int i = 0; i < cache.size(); i++) {
            if (cache.get(i).id.equals(id)) {
                cache.set(i, next);
                save();
                return;
            }
        }
        cache.add(next);
        save();
    }

    public synchronized String promptBlock() {
        List<LearningCandidate> pending = listPending();
        if (pending.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("Apprentissages en attente (hypothèses locales, pas encore validées) :\n");
        int n = 0;
        for (LearningCandidate c : pending) {
            if (n >= 3) break;
            sb.append("- ").append(c.title()).append(" : ").append(c.body()).append('\n');
            n++;
        }
        sb.append("Ne les présente comme des faits ; propose seulement si pertinent.\n");
        return sb.toString();
    }

    public synchronized void clearAll() {
        cache.clear();
        save();
    }

    private List<LearningCandidate> load() {
        List<LearningCandidate> out = new ArrayList<>();
        if (!file.isFile()) return out;
        try {
            byte[] raw = readAll(file);
            JSONObject root = new JSONObject(new String(raw, StandardCharsets.UTF_8));
            JSONArray arr = root.optJSONArray("candidates");
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                LearningCandidate c = LearningCandidate.fromJson(arr.optJSONObject(i));
                if (c != null) out.add(c);
            }
        } catch (Exception e) {
            Log.w(TAG, "load", e);
        }
        return out;
    }

    private void save() {
        try {
            JSONArray arr = new JSONArray();
            for (LearningCandidate c : cache) arr.put(c.toJson());
            JSONObject root = new JSONObject().put("candidates", arr);
            byte[] bytes = root.toString(2).getBytes(StandardCharsets.UTF_8);
            if (!file.getParentFile().isDirectory()) {
                //noinspection ResultOfMethodCallIgnored
                file.getParentFile().mkdirs();
            }
            File tmp = new File(file.getParentFile(), FILE + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(bytes);
                out.getFD().sync();
            }
            if (file.exists()) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
            if (!tmp.renameTo(file)) {
                try (FileOutputStream out = new FileOutputStream(file)) {
                    out.write(bytes);
                }
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        } catch (Exception e) {
            Log.w(TAG, "save", e);
        }
    }

    private static byte[] readAll(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) Math.max(0, f.length())];
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n < 0) break;
                off += n;
            }
            return buf;
        }
    }
}
