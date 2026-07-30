package com.pegasuscorp.orbe.rag;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

/**
 * Embedding local all-MiniLM-L6-v2 (ONNX) — 384 dims, 100 % on-device.
 * <p>
 * Réutilise l'OrtEnvironment déjà présent via sherpa-onnxruntime.
 */
public final class EmbeddingEngine {

    public static final int DIMENSIONS = 384;
    public static final int MAX_SEQ_LEN = 128;
    public static final String MODEL_ASSET = "rag/all-MiniLM-L6-v2.onnx";
    public static final String VOCAB_ASSET = "rag/vocab.txt";

    private static final String TAG = "EmbeddingEngine";

    private static EmbeddingEngine instance;

    private final OrtEnvironment env;
    private final OrtSession session;
    private final BertWordPieceTokenizer tokenizer;
    private final long loadMs;

    private EmbeddingEngine(OrtEnvironment env, OrtSession session,
            BertWordPieceTokenizer tokenizer, long loadMs) {
        this.env = env;
        this.session = session;
        this.tokenizer = tokenizer;
        this.loadMs = loadMs;
    }

    public static synchronized EmbeddingEngine get(Context ctx) throws Exception {
        if (instance == null) {
            instance = create(ctx.getApplicationContext());
        }
        return instance;
    }

    /** Remet à null (tests). */
    public static synchronized void resetForTests() {
        if (instance != null) {
            try {
                instance.session.close();
            } catch (Exception ignored) {}
            instance = null;
        }
    }

    /** Installe une instance préchargée (tests JVM sans assets Android). */
    public static synchronized void installForTests(EmbeddingEngine engine) {
        if (instance == engine) return;
        if (instance != null) resetForTests();
        instance = engine;
    }

    public static EmbeddingEngine create(Context ctx) throws Exception {
        long t0 = System.currentTimeMillis();
        OrtEnvironment ortEnv = OrtEnvironment.getEnvironment();
        File modelFile = materializeAsset(ctx, MODEL_ASSET, "all-MiniLM-L6-v2.onnx");
        BertWordPieceTokenizer tok;
        try (InputStream vin = ctx.getAssets().open(VOCAB_ASSET)) {
            tok = new BertWordPieceTokenizer(vin);
        }
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setIntraOpNumThreads(2);
        OrtSession sess = ortEnv.createSession(modelFile.getAbsolutePath(), opts);
        long loadMs = System.currentTimeMillis() - t0;
        Log.i(TAG, "Modèle chargé en " + loadMs + " ms → " + modelFile.length() + " octets");
        return new EmbeddingEngine(ortEnv, sess, tok, loadMs);
    }

    /** Pour tests JVM : chemins fichiers locaux. */
    public static EmbeddingEngine createFromFiles(File modelFile, InputStream vocabStream)
            throws Exception {
        long t0 = System.currentTimeMillis();
        OrtEnvironment ortEnv = OrtEnvironment.getEnvironment();
        BertWordPieceTokenizer tok = new BertWordPieceTokenizer(vocabStream);
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setIntraOpNumThreads(2);
        OrtSession sess = ortEnv.createSession(modelFile.getAbsolutePath(), opts);
        return new EmbeddingEngine(ortEnv, sess, tok, System.currentTimeMillis() - t0);
    }

    public long getLoadMs() {
        return loadMs;
    }

    public BertWordPieceTokenizer tokenizer() {
        return tokenizer;
    }

    /** Vecteur float[384] L2-normalisé. OrtSession n'est pas thread-safe en Run parallèle. */
    public synchronized float[] embed(String text) throws Exception {
        BertWordPieceTokenizer.Encoded enc = tokenizer.encode(text, MAX_SEQ_LEN);
        long[][] ids = new long[][]{enc.inputIds};
        long[][] mask = new long[][]{enc.attentionMask};
        long[][] types = new long[][]{enc.tokenTypeIds};

        try (OnnxTensor tIds = OnnxTensor.createTensor(env, ids);
             OnnxTensor tMask = OnnxTensor.createTensor(env, mask);
             OnnxTensor tTypes = OnnxTensor.createTensor(env, types)) {
            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("input_ids", tIds);
            inputs.put("attention_mask", tMask);
            inputs.put("token_type_ids", tTypes);
            try (OrtSession.Result result = session.run(inputs)) {
                float[][][] hidden = to3d(result.get(0).getValue());
                float[] pooled = VectorMath.meanPool(hidden[0], enc.attentionMask);
                return VectorMath.l2Normalize(pooled);
            }
        }
    }

    private static float[][][] to3d(Object value) {
        if (value instanceof float[][][]) {
            return (float[][][]) value;
        }
        if (value instanceof OnnxTensor) {
            throw new IllegalStateException("Tensor non extrait");
        }
        // Certains builds renvoient FloatBuffer via getFloatBuffer
        throw new IllegalStateException("Sortie ONNX inattendue : "
                + (value == null ? "null" : value.getClass().getName()));
    }

    private static File materializeAsset(Context ctx, String assetPath, String fileName)
            throws Exception {
        File dir = new File(ctx.getFilesDir(), "rag");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("Impossible de créer " + dir);
        }
        File out = new File(dir, fileName);
        // Recopie si absent ou taille différente (mise à jour modèle)
        long assetSize;
        try (InputStream in = ctx.getAssets().open(assetPath)) {
            assetSize = in.available();
        }
        if (out.exists() && out.length() == assetSize && assetSize > 1_000_000) {
            return out;
        }
        try (InputStream in = ctx.getAssets().open(assetPath);
             FileOutputStream fos = new FileOutputStream(out)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) >= 0) {
                fos.write(buf, 0, n);
            }
        }
        return out;
    }
}
