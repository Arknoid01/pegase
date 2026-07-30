package com.pegasuscorp.orbe.rag;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Stockage vectoriel local (SQLite + BLOB float32).
 * <p>
 * Recherche par cosine en Java — assez rapide pour des milliers de souvenirs,
 * sans extension sqlite-vss (intégration Android fragile).
 * <p>
 * Namespace : {@code ""} = mémoire conversationnelle ;
 * {@link #NS_DIAG} = historique de comportement (DiagTool).
 */
public final class VectorStore {

    public static final String NS_MEMORY = "";
    public static final String NS_DIAG = "diag";
    /** Exemples de routing utilisateur (phrase → outil). */
    public static final String NS_ROUTING = "routing";
    /** Index sémantique des fichiers de projets Orion. */
    public static final String NS_ORION_FILES = "orion_files";
    /** Index AST Java (méthodes / champs) des projets Orion. */
    public static final String NS_ORION_CODE = "orion_code";

    public static final int DEFAULT_DIMS = EmbeddingEngine.DIMENSIONS;
    private static final int SCHEMA_VERSION = 2;

    public static final class Hit {
        public final String memoryKey;
        public final float score;
        public final String namespace;
        public final String payload;
        public final long createdAtMs;

        public Hit(String memoryKey, float score) {
            this(memoryKey, score, NS_MEMORY, null, 0L);
        }

        public Hit(String memoryKey, float score, String namespace, String payload,
                long createdAtMs) {
            this.memoryKey = memoryKey;
            this.score = score;
            this.namespace = namespace != null ? namespace : NS_MEMORY;
            this.payload = payload;
            this.createdAtMs = createdAtMs;
        }
    }

    private final SQLiteDatabase db;
    private final int dims;

    public VectorStore(Context ctx) {
        this(ctx, DEFAULT_DIMS);
    }

    public VectorStore(Context ctx, int dims) {
        if (dims <= 0) throw new IllegalArgumentException("dims");
        this.dims = dims;
        File dir = new File(ctx.getApplicationContext().getFilesDir(), "memory");
        if (!dir.exists()) dir.mkdirs();
        File dbFile = new File(dir, "vectors.db");
        this.db = SQLiteDatabase.openOrCreateDatabase(dbFile, null);
        migrate(db);
    }

    /** Tests : base en mémoire. */
    public VectorStore(int dims, boolean inMemory) {
        if (!inMemory) throw new IllegalArgumentException("use Context ctor");
        if (dims <= 0) throw new IllegalArgumentException("dims");
        this.dims = dims;
        this.db = SQLiteDatabase.create(null);
        migrate(db);
    }

    private static void migrate(SQLiteDatabase db) {
        db.execSQL(
                "CREATE TABLE IF NOT EXISTS memory_vectors ("
                        + "memory_key TEXT PRIMARY KEY NOT NULL,"
                        + "dims INTEGER NOT NULL,"
                        + "embedding BLOB NOT NULL,"
                        + "updated_at INTEGER NOT NULL"
                        + ")");
        int ver = readUserVersion(db);
        if (ver < 2) {
            addColumnIfMissing(db, "memory_vectors", "namespace", "TEXT NOT NULL DEFAULT ''");
            addColumnIfMissing(db, "memory_vectors", "payload", "TEXT");
            addColumnIfMissing(db, "memory_vectors", "created_at", "INTEGER NOT NULL DEFAULT 0");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_memory_vectors_ns "
                    + "ON memory_vectors(namespace)");
            // Renseigner created_at manquant
            db.execSQL("UPDATE memory_vectors SET created_at=updated_at "
                    + "WHERE created_at=0 OR created_at IS NULL");
        }
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_memory_vectors_dims "
                + "ON memory_vectors(dims)");
        db.execSQL("PRAGMA user_version=" + SCHEMA_VERSION);
    }

    private static int readUserVersion(SQLiteDatabase db) {
        try (Cursor c = db.rawQuery("PRAGMA user_version", null)) {
            if (c.moveToFirst()) return c.getInt(0);
        } catch (Exception ignored) {}
        return 0;
    }

    private static void addColumnIfMissing(SQLiteDatabase db, String table,
            String column, String typeSql) {
        boolean has = false;
        try (Cursor c = db.rawQuery("PRAGMA table_info(" + table + ")", null)) {
            while (c.moveToNext()) {
                if (column.equalsIgnoreCase(c.getString(1))) {
                    has = true;
                    break;
                }
            }
        }
        if (!has) {
            db.execSQL("ALTER TABLE " + table + " ADD COLUMN " + column + " " + typeSql);
        }
    }

    /** Clé stable pour un souvenir JSON (category + content). */
    public static String keyFor(String category, String content) {
        String raw = (category == null ? "" : category.trim().toLowerCase(Locale.ROOT))
                + "\n"
                + (content == null ? "" : content.trim());
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                sb.append(String.format(Locale.US, "%02x", dig[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(raw.hashCode());
        }
    }

    public int dimensions() {
        return dims;
    }

    /** Mémoire conversationnelle (namespace vide). */
    public void upsert(String memoryKey, float[] vector) {
        upsert(memoryKey, vector, NS_MEMORY, null, System.currentTimeMillis());
    }

    public void upsert(String memoryKey, float[] vector, String namespace, String payload) {
        upsert(memoryKey, vector, namespace, payload, System.currentTimeMillis());
    }

    public void upsert(String memoryKey, float[] vector, String namespace, String payload,
            long createdAtMs) {
        if (memoryKey == null || memoryKey.isEmpty()) {
            throw new IllegalArgumentException("memoryKey vide");
        }
        if (vector == null || vector.length != dims) {
            throw new IllegalArgumentException(
                    "vecteur attendu float[" + dims + "], reçu "
                            + (vector == null ? "null" : vector.length));
        }
        String ns = namespace != null ? namespace : NS_MEMORY;
        long now = System.currentTimeMillis();
        long created = createdAtMs > 0 ? createdAtMs : now;
        if (hasVector(memoryKey)) {
            long existing = getCreatedAt(memoryKey);
            if (existing > 0) created = existing;
        }
        ContentValues cv = new ContentValues();
        cv.put("memory_key", memoryKey);
        cv.put("dims", dims);
        cv.put("embedding", floatsToBytes(vector));
        cv.put("updated_at", now);
        cv.put("namespace", ns);
        cv.put("payload", payload);
        cv.put("created_at", created);
        db.insertWithOnConflict("memory_vectors", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public boolean hasVector(String memoryKey) {
        if (memoryKey == null) return false;
        try (Cursor c = db.rawQuery(
                "SELECT 1 FROM memory_vectors WHERE memory_key=? LIMIT 1",
                new String[]{memoryKey})) {
            return c.moveToFirst();
        }
    }

    private long getCreatedAt(String memoryKey) {
        try (Cursor c = db.rawQuery(
                "SELECT created_at FROM memory_vectors WHERE memory_key=? LIMIT 1",
                new String[]{memoryKey})) {
            if (c.moveToFirst()) return c.getLong(0);
        } catch (Exception ignored) {}
        return 0L;
    }

    public void delete(String memoryKey) {
        if (memoryKey == null) return;
        db.delete("memory_vectors", "memory_key=?", new String[]{memoryKey});
    }

    /** Supprime les clés d'un namespace commençant par {@code prefix}. */
    public int deleteKeysWithPrefix(String namespace, String prefix) {
        if (prefix == null || prefix.isEmpty()) return 0;
        String ns = namespace != null ? namespace : NS_MEMORY;
        return db.delete("memory_vectors",
                "namespace=? AND memory_key LIKE ?",
                new String[]{ns, prefix + "%"});
    }

    /**
     * Purge un namespace : lignes plus vieilles que {@code keepDays}.
     * @return nombre de lignes supprimées
     */
    public int purgeNamespaceOlderThan(String namespace, int keepDays) {
        String ns = namespace != null ? namespace : NS_MEMORY;
        long cutoff = System.currentTimeMillis()
                - Math.max(1, keepDays) * 24L * 60L * 60L * 1000L;
        return db.delete("memory_vectors",
                "namespace=? AND created_at>0 AND created_at<?",
                new String[]{ns, String.valueOf(cutoff)});
    }

    public int size() {
        try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM memory_vectors", null)) {
            if (c.moveToFirst()) return c.getInt(0);
            return 0;
        }
    }

    public int size(String namespace) {
        String ns = namespace != null ? namespace : NS_MEMORY;
        try (Cursor c = db.rawQuery(
                "SELECT COUNT(*) FROM memory_vectors WHERE namespace=?",
                new String[]{ns})) {
            if (c.moveToFirst()) return c.getInt(0);
            return 0;
        }
    }

    public void clear() {
        db.delete("memory_vectors", null, null);
    }

    public void clearNamespace(String namespace) {
        String ns = namespace != null ? namespace : NS_MEMORY;
        db.delete("memory_vectors", "namespace=?", new String[]{ns});
    }

    /** Recherche dans le namespace mémoire (défaut) — isolé du diag. */
    public List<Hit> search(float[] queryVector, int topK, float minScore) {
        return search(queryVector, topK, minScore, NS_MEMORY);
    }

    /**
     * Top-K cosine dans un namespace donné.
     * @param namespace {@link #NS_MEMORY}, {@link #NS_DIAG}, …
     */
    public List<Hit> search(float[] queryVector, int topK, float minScore, String namespace) {
        if (queryVector == null || queryVector.length != dims) {
            throw new IllegalArgumentException("queryVector float[" + dims + "]");
        }
        if (topK <= 0) return new ArrayList<>();
        String ns = namespace != null ? namespace : NS_MEMORY;

        List<Hit> all = new ArrayList<>();
        try (Cursor c = db.rawQuery(
                "SELECT memory_key, embedding, namespace, payload, created_at "
                        + "FROM memory_vectors WHERE dims=? AND namespace=?",
                new String[]{String.valueOf(dims), ns})) {
            while (c.moveToNext()) {
                String key = c.getString(0);
                float[] vec = bytesToFloats(c.getBlob(1), dims);
                if (vec == null) continue;
                float score = VectorMath.cosineSimilarity(queryVector, vec);
                if (score >= minScore) {
                    all.add(new Hit(key, score, c.getString(2), c.getString(3), c.getLong(4)));
                }
            }
        }
        all.sort(Comparator.comparingDouble((Hit h) -> h.score).reversed());
        if (all.size() <= topK) return all;
        return new ArrayList<>(all.subList(0, topK));
    }

    public void close() {
        if (db.isOpen()) db.close();
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
        for (int i = 0; i < dims; i++) {
            out[i] = buf.getFloat();
        }
        return out;
    }
}
