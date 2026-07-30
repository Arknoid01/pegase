package com.pegasuscorp.orbe.rag;

/** Opérations vectorielles pour le RAG local. */
public final class VectorMath {

    private VectorMath() {}

    /** Mean pooling masqué sur last_hidden_state [seq][dim]. */
    public static float[] meanPool(float[][] tokenEmbeddings, long[] attentionMask) {
        int dim = tokenEmbeddings[0].length;
        float[] out = new float[dim];
        float count = 0f;
        int n = Math.min(tokenEmbeddings.length, attentionMask.length);
        for (int i = 0; i < n; i++) {
            if (attentionMask[i] == 0) continue;
            float[] t = tokenEmbeddings[i];
            for (int d = 0; d < dim; d++) {
                out[d] += t[d];
            }
            count += 1f;
        }
        if (count < 1f) count = 1f;
        for (int d = 0; d < dim; d++) {
            out[d] /= count;
        }
        return out;
    }

    public static float[] l2Normalize(float[] v) {
        double sum = 0;
        for (float x : v) sum += (double) x * x;
        float norm = (float) Math.sqrt(sum);
        if (norm < 1e-12f) return v.clone();
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = v[i] / norm;
        }
        return out;
    }

    public static float cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) return 0f;
        float dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        float denom = (float) (Math.sqrt(na) * Math.sqrt(nb));
        if (denom < 1e-12f) return 0f;
        return dot / denom;
    }
}
