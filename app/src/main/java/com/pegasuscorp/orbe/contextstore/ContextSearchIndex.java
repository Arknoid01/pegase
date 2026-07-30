package com.pegasuscorp.orbe.contextstore;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import com.pegasuscorp.orbe.rag.EmbeddingEngine;
import com.pegasuscorp.orbe.rag.VectorMath;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Index sémantique des contextes .md (chunks par section ##).
 * Base dédiée : {@code files/contexts/context_vectors.db}.
 */
public final class ContextSearchIndex {

    private static final String TAG = "ContextSearchIndex";
    /** Seuil cosine MiniLM (aligné mémoire ; le doc 0.70 est trop strict). */
    public static final float MIN_SCORE = 0.30f;
    private static final int MAX_CHUNK_CHARS = 900;

    public static final class Hit {
        public final String filename;
        public final String displayName;
        public final String chunk;
        public final float score;

        public Hit(String filename, String displayName, String chunk, float score) {
            this.filename = filename;
            this.displayName = displayName;
            this.chunk = chunk;
            this.score = score;
        }
    }

    private static ContextSearchIndex instance;
    private static volatile boolean autoIndex = true;
    private static final AtomicBoolean indexing = new AtomicBoolean(false);

    private final Context appContext;
    private final File contextsDir;
    private final SQLiteDatabase db;
    private final int dims = EmbeddingEngine.DIMENSIONS;

    private ContextSearchIndex(Context context) {
        appContext = context.getApplicationContext();
        contextsDir = new File(appContext.getFilesDir(), "contexts");
        if (!contextsDir.exists()) contextsDir.mkdirs();
        File dbFile = new File(contextsDir, "context_vectors.db");
        db = SQLiteDatabase.openOrCreateDatabase(dbFile, null);
        migrate(db);
    }

    public static synchronized ContextSearchIndex getInstance(Context context) {
        if (instance == null) instance = new ContextSearchIndex(context);
        return instance;
    }

    public static synchronized void resetInstanceForTests() {
        if (instance != null) {
            try {
                instance.db.close();
            } catch (Exception ignored) {}
            instance = null;
        }
    }

    public static void setAutoIndexForTests(boolean enabled) {
        autoIndex = enabled;
    }

    private static void migrate(SQLiteDatabase db) {
        db.execSQL(
                "CREATE TABLE IF NOT EXISTS context_chunks ("
                        + "chunk_key TEXT PRIMARY KEY NOT NULL,"
                        + "filename TEXT NOT NULL,"
                        + "chunk_text TEXT NOT NULL,"
                        + "dims INTEGER NOT NULL,"
                        + "embedding BLOB NOT NULL,"
                        + "updated_at INTEGER NOT NULL"
                        + ")");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_ctx_chunks_file ON context_chunks(filename)");
    }

    /** Indexe tous les .md en arrière-plan (au démarrage). */
    public void indexAllAsync() {
        if (!autoIndex) return;
        if (!indexing.compareAndSet(false, true)) return;
        new Thread(() -> {
            try {
                indexAllNow();
            } finally {
                indexing.set(false);
            }
        }, "ctx-rag-index").start();
    }

    /** @return nombre de chunks (ré)indexés */
    public int indexAllNow() {
        File[] files = contextsDir.listFiles((dir, name) ->
                name != null && name.endsWith(".md"));
        if (files == null || files.length == 0) return 0;
        int n = 0;
        for (File f : files) {
            n += indexFile(f);
        }
        Log.i(TAG, "Index contextes : " + n + " chunks (" + files.length + " fichiers)");
        return n;
    }

    public int indexFile(File file) {
        if (file == null || !file.isFile()) return 0;
        String filename = file.getName();
        String content = ContextualFileStore.readUtf8Public(file);
        if (content == null || content.isEmpty()) {
            deleteFilename(filename);
            return 0;
        }
        return indexContent(filename, content);
    }

    public int indexContent(String filename, String content) {
        if (filename == null || content == null) return 0;
        try {
            EmbeddingEngine engine = EmbeddingEngine.get(appContext);
            List<String> chunks = chunkMarkdown(content);
            deleteFilename(filename);
            int n = 0;
            for (String chunk : chunks) {
                String key = chunkKey(filename, chunk);
                float[] vector = engine.embed(chunk);
                ContentValues cv = new ContentValues();
                cv.put("chunk_key", key);
                cv.put("filename", filename);
                cv.put("chunk_text", chunk);
                cv.put("dims", dims);
                cv.put("embedding", floatsToBytes(vector));
                cv.put("updated_at", System.currentTimeMillis());
                db.insertWithOnConflict("context_chunks", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
                n++;
            }
            return n;
        } catch (Exception e) {
            Log.w(TAG, "indexContent " + filename, e);
            return 0;
        }
    }

    public void deleteFilename(String filename) {
        if (filename == null) return;
        db.delete("context_chunks", "filename=?", new String[]{filename});
    }

    public List<Hit> search(String query, int topK, float minScore) {
        List<Hit> out = new ArrayList<>();
        if (query == null || query.trim().isEmpty()) return out;
        try {
            float[] qv = EmbeddingEngine.get(appContext).embed(query.trim());
            List<Hit> all = new ArrayList<>();
            try (Cursor c = db.rawQuery(
                    "SELECT filename, chunk_text, embedding FROM context_chunks WHERE dims=?",
                    new String[]{String.valueOf(dims)})) {
                while (c.moveToNext()) {
                    String filename = c.getString(0);
                    String chunk = c.getString(1);
                    float[] vec = bytesToFloats(c.getBlob(2), dims);
                    if (vec == null) continue;
                    float score = VectorMath.cosineSimilarity(qv, vec);
                    if (score >= minScore) {
                        all.add(new Hit(filename, displayName(filename), chunk, score));
                    }
                }
            }
            all.sort(Comparator.comparingDouble((Hit h) -> h.score).reversed());
            // Déduplique par fichier (meilleur chunk)
            Map<String, Hit> bestPerFile = new LinkedHashMap<>();
            for (Hit h : all) {
                if (!bestPerFile.containsKey(h.filename)) {
                    bestPerFile.put(h.filename, h);
                }
            }
            out.addAll(bestPerFile.values());
            if (out.size() > topK) {
                return new ArrayList<>(out.subList(0, topK));
            }
            return out;
        } catch (Exception e) {
            Log.w(TAG, "search", e);
            return out;
        }
    }

    public String formatSearchForSpeech(List<Hit> hits, String query) {
        if (hits == null || hits.isEmpty()) {
            return "Je n'ai rien trouvé dans tes fichiers de contexte pour « "
                    + (query == null ? "" : query) + " ».";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Trouvé dans ");
        for (int i = 0; i < hits.size(); i++) {
            if (i > 0) sb.append(i == hits.size() - 1 ? " et " : ", ");
            sb.append(hits.get(i).displayName);
        }
        sb.append(". ");
        Hit top = hits.get(0);
        String excerpt = top.chunk.replace('\n', ' ').trim();
        if (excerpt.length() > 160) excerpt = excerpt.substring(0, 157) + "…";
        sb.append("Extrait de ").append(top.displayName).append(" : ").append(excerpt);
        return sb.toString();
    }

    public String formatSearchForLlm(List<Hit> hits) {
        if (hits == null || hits.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("--- Recherche contextes ---\n");
        for (Hit h : hits) {
            sb.append("[").append(h.filename).append(" score=")
                    .append(String.format(Locale.US, "%.2f", h.score)).append("]\n")
                    .append(h.chunk.trim()).append("\n\n");
        }
        return sb.toString();
    }

    /** Découpe un markdown en sections ## (+ sous-chunks si trop long). */
    static List<String> chunkMarkdown(String content) {
        List<String> sections = new ArrayList<>();
        if (content == null || content.trim().isEmpty()) return sections;
        String[] lines = content.split("\n", -1);
        StringBuilder current = new StringBuilder();
        for (String line : lines) {
            if (line.startsWith("## ") && current.length() > 0) {
                addChunkParts(sections, current.toString().trim());
                current.setLength(0);
            }
            if (current.length() > 0) current.append('\n');
            current.append(line);
        }
        if (current.length() > 0) {
            addChunkParts(sections, current.toString().trim());
        }
        if (sections.isEmpty()) {
            addChunkParts(sections, content.trim());
        }
        return sections;
    }

    private static void addChunkParts(List<String> out, String text) {
        if (text == null || text.isEmpty()) return;
        if (text.length() <= MAX_CHUNK_CHARS) {
            out.add(text);
            return;
        }
        String[] paras = text.split("\n\n+");
        StringBuilder buf = new StringBuilder();
        for (String p : paras) {
            if (buf.length() + p.length() + 2 > MAX_CHUNK_CHARS && buf.length() > 0) {
                out.add(buf.toString().trim());
                buf.setLength(0);
            }
            if (buf.length() > 0) buf.append("\n\n");
            buf.append(p.trim());
        }
        if (buf.length() > 0) out.add(buf.toString().trim());
    }

    static String chunkKey(String filename, String chunk) {
        String raw = filename + "\n" + chunk;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("ctx:");
            for (int i = 0; i < 10; i++) {
                sb.append(String.format(Locale.US, "%02x", dig[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return "ctx:" + Integer.toHexString(raw.hashCode());
        }
    }

    private static String displayName(String filename) {
        if (filename == null) return "";
        String s = filename.replace("-context.md", "").replace(".md", "");
        if (s.isEmpty()) return filename;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    static byte[] floatsToBytes(float[] v) {
        ByteBuffer buf = ByteBuffer.allocate(v.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float x : v) buf.putFloat(x);
        return buf.array();
    }

    static float[] bytesToFloats(byte[] raw, int dims) {
        if (raw == null || raw.length < dims * 4) return null;
        ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        float[] out = new float[dims];
        for (int i = 0; i < dims; i++) out[i] = buf.getFloat();
        return out;
    }
}
