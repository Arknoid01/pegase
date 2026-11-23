package com.pegasuscorp.orbe.search;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** BM25Okapi identique à rank_bm25 (k1 = 1,5 ; b = 0,75 ; idf négatif → 0,25 × idf moyen). */
final class Bm25 {

    private static final double K1 = 1.5, B = 0.75, EPS = 0.25;

    private final List<Map<String, Integer>> freqs = new java.util.ArrayList<>();
    private final int[] lens;
    private final double avgdl;
    private final Map<String, Double> idf = new HashMap<>();

    Bm25(List<List<String>> corpus) {
        lens = new int[corpus.size()];
        Map<String, Integer> df = new HashMap<>();
        long total = 0;
        for (int i = 0; i < corpus.size(); i++) {
            List<String> doc = corpus.get(i);
            lens[i] = doc.size();
            total += doc.size();
            Map<String, Integer> f = new HashMap<>();
            for (String w : doc) f.merge(w, 1, Integer::sum);
            freqs.add(f);
            for (String w : f.keySet()) df.merge(w, 1, Integer::sum);
        }
        avgdl = corpus.isEmpty() ? 0 : (double) total / corpus.size();
        int n = corpus.size();
        double sum = 0;
        java.util.List<String> negatifs = new java.util.ArrayList<>();
        for (Map.Entry<String, Integer> e : df.entrySet()) {
            double v = Math.log(n - e.getValue() + 0.5) - Math.log(e.getValue() + 0.5);
            idf.put(e.getKey(), v);
            sum += v;
            if (v < 0) negatifs.add(e.getKey());
        }
        double plancher = EPS * (df.isEmpty() ? 0 : sum / df.size());
        for (String w : negatifs) idf.put(w, plancher);
    }

    double[] scores(List<String> query) {
        double[] s = new double[lens.length];
        for (String q : query) {
            Double id = idf.get(q);
            double v = id == null ? 0 : id;
            for (int i = 0; i < lens.length; i++) {
                Integer f = freqs.get(i).get(q);
                double tf = f == null ? 0 : f;
                s[i] += v * (tf * (K1 + 1) / (tf + K1 * (1 - B + B * lens[i] / avgdl)));
            }
        }
        return s;
    }
}
