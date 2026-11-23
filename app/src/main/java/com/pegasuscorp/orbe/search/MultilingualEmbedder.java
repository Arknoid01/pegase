package com.pegasuscorp.orbe.search;

import com.pegasuscorp.orbe.rag.VectorMath;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

/**
 * Embeddings multilingues du moteur de recherche privé : paraphrase-multilingual-MiniLM-L12-v2
 * (ONNX de Qdrant, le MÊME fichier que le prototype PC → mêmes scores que le banc).
 * Moyenne des jetons masquée puis normalisation L2, comme fastembed.
 */
public final class MultilingualEmbedder implements AutoCloseable {

    public static final String MODEL_FILE = "model_optimized.onnx";
    public static final String TOKENIZER_FILE = "tokenizer.json";
    /** Téléchargement (Wi-Fi) depuis Hugging Face — pas d'hébergement à nous. */
    public static final String HF_BASE =
            "https://huggingface.co/Qdrant/paraphrase-multilingual-MiniLM-L12-v2-onnx-Q/resolve/main/";

    private final OrtEnvironment env;
    private final OrtSession session;
    private final UnigramTokenizer tokenizer;

    private MultilingualEmbedder(OrtEnvironment env, OrtSession session, UnigramTokenizer tok) {
        this.env = env;
        this.session = session;
        this.tokenizer = tok;
    }

    /** {@code dir} contient model_optimized.onnx et tokenizer.json. */
    public static MultilingualEmbedder open(File dir) throws Exception {
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setIntraOpNumThreads(4);
        OrtSession s = env.createSession(new File(dir, MODEL_FILE).getAbsolutePath(), opts);
        UnigramTokenizer t = UnigramTokenizer.loadCached(new File(dir, TOKENIZER_FILE));
        return new MultilingualEmbedder(env, s, t);
    }

    public static boolean isInstalled(File dir) {
        File m = new File(dir, MODEL_FILE), t = new File(dir, TOKENIZER_FILE);
        return m.length() > 100_000_000L && t.length() > 10_000_000L;
    }

    public UnigramTokenizer tokenizer() {
        return tokenizer;
    }

    /** Vecteurs L2-normalisés (384), par lots de 16 phrases rembourrées à la plus longue. */
    public synchronized float[][] embed(List<String> texts) throws Exception {
        float[][] out = new float[texts.size()][];
        final int lot = 16;
        for (int start = 0; start < texts.size(); start += lot) {
            int end = Math.min(texts.size(), start + lot);
            long[][] enc = new long[end - start][];
            int maxLen = 1;
            for (int i = start; i < end; i++) {
                enc[i - start] = tokenizer.encode(texts.get(i));
                maxLen = Math.max(maxLen, enc[i - start].length);
            }
            long[][] ids = new long[enc.length][maxLen];
            long[][] mask = new long[enc.length][maxLen];
            long[][] types = new long[enc.length][maxLen];
            for (int r = 0; r < enc.length; r++) {
                for (int c = 0; c < maxLen; c++) {
                    boolean in = c < enc[r].length;
                    ids[r][c] = in ? enc[r][c] : tokenizer.padId();
                    mask[r][c] = in ? 1 : 0;
                }
            }
            try (OnnxTensor tIds = OnnxTensor.createTensor(env, ids);
                 OnnxTensor tMask = OnnxTensor.createTensor(env, mask);
                 OnnxTensor tTypes = OnnxTensor.createTensor(env, types)) {
                Map<String, OnnxTensor> inputs = new HashMap<>();
                inputs.put("input_ids", tIds);
                inputs.put("attention_mask", tMask);
                inputs.put("token_type_ids", tTypes);
                try (OrtSession.Result res = session.run(inputs)) {
                    float[][][] hidden = (float[][][]) res.get(0).getValue();
                    for (int r = 0; r < enc.length; r++) {
                        out[start + r] = VectorMath.l2Normalize(
                                VectorMath.meanPool(hidden[r], mask[r]));
                    }
                }
            }
        }
        return out;
    }

    @Override
    public void close() throws Exception {
        session.close();
    }
}
