package com.pegasuscorp.orbe.search;

import android.util.JsonReader;
import android.util.JsonToken;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Découpeur SentencePiece « Unigram » (tokenizer.json de Hugging Face) pour le modèle
 * paraphrase-multilingual-MiniLM-L12-v2 du moteur de recherche privé.
 *
 * <p>Reproduit le pipeline HF : normalisation (≈ NFKC), découpage aux espaces, préfixe « ▁ »
 * à chaque mot, Viterbi sur les scores des morceaux, inconnus fusionnés, puis {@code <s>} … {@code </s>}
 * et coupe à {@link #MAX_LEN} jetons. Vérifié contre la bibliothèque Python {@code tokenizers}
 * (UnigramTokenizerTest).
 */
public final class UnigramTokenizer {

    public static final int MAX_LEN = 128;
    private static final String META = "▁";

    private final Map<String, Integer> ids;
    private final float[] scores;
    private final int unkId, bosId, eosId, padId;
    private final float unkScore;
    private final int maxPieceLen;

    private UnigramTokenizer(List<String> pieces, float[] scores, int unkId) {
        this.ids = new HashMap<>(pieces.size() * 2);
        int maxLen = 1;
        float min = 0f;
        for (int i = 0; i < pieces.size(); i++) {
            String p = pieces.get(i);
            ids.put(p, i);
            maxLen = Math.max(maxLen, p.length());
            min = Math.min(min, scores[i]);
        }
        this.scores = scores;
        this.unkId = unkId;
        this.unkScore = min - 10f;   // comme HF : inconnu = score minimal − 10
        this.maxPieceLen = maxLen;
        this.bosId = idOr("<s>", 0);
        this.eosId = idOr("</s>", 2);
        this.padId = idOr("<pad>", 1);
    }

    private int idOr(String piece, int def) {
        Integer i = ids.get(piece);
        return i != null ? i : def;
    }

    public int padId() {
        return padId;
    }

    // ---------- chargement ----------

    /** Lit tokenizer.json (17 Mo) en flux ; préférer {@link #loadCached} sur le téléphone. */
    public static UnigramTokenizer fromTokenizerJson(InputStream in) throws Exception {
        List<String> pieces = new ArrayList<>(260_000);
        List<Float> sc = new ArrayList<>(260_000);
        int unk = 3;
        try (JsonReader r = new JsonReader(new InputStreamReader(new BufferedInputStream(in),
                StandardCharsets.UTF_8))) {
            r.beginObject();
            while (r.hasNext()) {
                String name = r.nextName();
                if (!"model".equals(name)) {
                    r.skipValue();
                    continue;
                }
                r.beginObject();
                while (r.hasNext()) {
                    String k = r.nextName();
                    if ("unk_id".equals(k) && r.peek() == JsonToken.NUMBER) {
                        unk = r.nextInt();
                    } else if ("vocab".equals(k)) {
                        r.beginArray();
                        while (r.hasNext()) {
                            r.beginArray();
                            pieces.add(r.nextString());
                            sc.add((float) r.nextDouble());
                            r.endArray();
                        }
                        r.endArray();
                    } else {
                        r.skipValue();
                    }
                }
                r.endObject();
            }
            r.endObject();
        }
        float[] s = new float[sc.size()];
        for (int i = 0; i < s.length; i++) s[i] = sc.get(i);
        return new UnigramTokenizer(pieces, s, unk);
    }

    /** tokenizer.json lu une fois puis mis en cache binaire à côté ({@code .bin}, lecture rapide). */
    public static UnigramTokenizer loadCached(File tokenizerJson) throws Exception {
        File bin = new File(tokenizerJson.getPath() + ".bin");
        if (bin.exists() && bin.lastModified() >= tokenizerJson.lastModified()) {
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                    new FileInputStream(bin), 1 << 16))) {
                int unk = in.readInt();
                int n = in.readInt();
                List<String> pieces = new ArrayList<>(n);
                float[] s = new float[n];
                for (int i = 0; i < n; i++) {
                    pieces.add(in.readUTF());
                    s[i] = in.readFloat();
                }
                return new UnigramTokenizer(pieces, s, unk);
            } catch (Exception ignored) {
                // cache abîmé : on relit le JSON
            }
        }
        UnigramTokenizer t;
        try (InputStream in = new FileInputStream(tokenizerJson)) {
            t = fromTokenizerJson(in);
        }
        File tmp = new File(bin.getPath() + ".tmp");
        try {
            ecrireCache(t, tmp);
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(bin);
        } catch (Exception ignored) {
            // dossier non inscriptible (modèle copié par adb…) : on relira le JSON la prochaine fois
        }
        return t;
    }

    private static void ecrireCache(UnigramTokenizer t, File tmp) throws Exception {
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
                new FileOutputStream(tmp), 1 << 16))) {
            String[] byId = new String[t.scores.length];
            for (Map.Entry<String, Integer> e : t.ids.entrySet()) byId[e.getValue()] = e.getKey();
            out.writeInt(t.unkId);
            out.writeInt(byId.length);
            for (int i = 0; i < byId.length; i++) {
                out.writeUTF(byId[i] != null ? byId[i] : "");
                out.writeFloat(t.scores[i]);
            }
        }
    }

    // ---------- encodage ----------

    /** Identifiants avec {@code <s>} et {@code </s>}, coupés à {@link #MAX_LEN}. */
    public long[] encode(String text) {
        List<Integer> out = new ArrayList<>();
        out.add(bosId);
        String norm = normalize(text);
        for (String word : norm.split("\\s+")) {
            if (word.isEmpty()) continue;
            viterbi(META + word, out);
            if (out.size() >= MAX_LEN - 1) break;
        }
        while (out.size() > MAX_LEN - 1) out.remove(out.size() - 1);
        out.add(eosId);
        long[] r = new long[out.size()];
        for (int i = 0; i < r.length; i++) r[i] = out.get(i);
        return r;
    }

    /** ≈ normaliseur « Precompiled » de SentencePiece (nmt_nfkc) : NFKC + espaces unifiés. */
    static String normalize(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFKC);
        StringBuilder sb = new StringBuilder(n.length());
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (Character.isWhitespace(c) || Character.isSpaceChar(c)) sb.append(' ');
            else if (Character.getType(c) == Character.CONTROL || c == '​' || c == '﻿') continue;
            else sb.append(c);
        }
        return sb.toString().trim();
    }

    /** Meilleur découpage (somme des scores maximale) ; inconnus consécutifs fusionnés. */
    private void viterbi(String w, List<Integer> out) {
        int n = w.length();
        float[] best = new float[n + 1];
        int[] prev = new int[n + 1];
        int[] tok = new int[n + 1];
        for (int i = 1; i <= n; i++) best[i] = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            if (best[i] == Float.NEGATIVE_INFINITY) continue;
            // Une paire de substitution (emoji…) compte pour un seul caractère
            int step = Character.isHighSurrogate(w.charAt(i)) && i + 1 < n ? 2 : 1;
            boolean single = false;
            int lim = Math.min(n, i + maxPieceLen);
            for (int j = i + 1; j <= lim; j++) {
                Integer id = ids.get(w.substring(i, j));
                if (id == null) continue;
                if (j == i + step) single = true;
                float s = best[i] + scores[id];
                if (s > best[j]) {
                    best[j] = s;
                    prev[j] = i;
                    tok[j] = id;
                }
            }
            if (!single) {
                int j = i + step;
                float s = best[i] + unkScore;
                if (s > best[j]) {
                    best[j] = s;
                    prev[j] = i;
                    tok[j] = unkId;
                }
            }
        }
        List<Integer> rev = new ArrayList<>();
        for (int j = n; j > 0; j = prev[j]) rev.add(tok[j]);
        int last = -1;
        for (int k = rev.size() - 1; k >= 0; k--) {
            int id = rev.get(k);
            if (id == unkId && last == unkId) continue;   // fuse_unk
            out.add(id);
            last = id;
        }
    }
}
