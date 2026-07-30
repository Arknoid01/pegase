package com.pegasuscorp.orbe.rag;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tokenizer WordPiece (BERT uncased) pour all-MiniLM-L6-v2 — vocab.txt HuggingFace.
 */
public final class BertWordPieceTokenizer {

    public static final int PAD_ID = 0;
    public static final int UNK_ID = 100;
    public static final int CLS_ID = 101;
    public static final int SEP_ID = 102;

    private static final Pattern PUNCT_SPLIT = Pattern.compile(
            "(\\p{P}|\\p{S})");

    private final Map<String, Integer> vocab;

    public BertWordPieceTokenizer(InputStream vocabStream) throws Exception {
        vocab = new HashMap<>(30_522);
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(vocabStream, StandardCharsets.UTF_8))) {
            String line;
            int id = 0;
            while ((line = br.readLine()) != null) {
                vocab.put(line, id++);
            }
        }
        if (!vocab.containsKey("[CLS]") || !vocab.containsKey("[SEP]")) {
            throw new IllegalStateException("vocab.txt MiniLM invalide (CLS/SEP manquants)");
        }
    }

    /**
     * Encode un texte : [CLS] … tokens … [SEP] + padding jusqu'à {@code maxLen}.
     * @return ids + attention_mask + token_type_ids (tous longueur maxLen)
     */
    public Encoded encode(String text, int maxLen) {
        if (maxLen < 4) throw new IllegalArgumentException("maxLen trop petit");
        List<Integer> tokens = new ArrayList<>();
        tokens.add(CLS_ID);
        for (String word : basicTokenize(text)) {
            for (int id : wordPiece(word)) {
                if (tokens.size() >= maxLen - 1) break;
                tokens.add(id);
            }
            if (tokens.size() >= maxLen - 1) break;
        }
        tokens.add(SEP_ID);

        long[] ids = new long[maxLen];
        long[] mask = new long[maxLen];
        long[] types = new long[maxLen];
        for (int i = 0; i < tokens.size() && i < maxLen; i++) {
            ids[i] = tokens.get(i);
            mask[i] = 1;
        }
        return new Encoded(ids, mask, types);
    }

    List<String> basicTokenize(String text) {
        if (text == null) text = "";
        String t = text.toLowerCase(Locale.ROOT).trim();
        t = Normalizer.normalize(t, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        t = t.replaceAll("\\s+", " ");
        List<String> out = new ArrayList<>();
        for (String part : t.split(" ")) {
            if (part.isEmpty()) continue;
            out.addAll(splitOnPunctuation(part));
        }
        return out;
    }

    private static List<String> splitOnPunctuation(String token) {
        List<String> out = new ArrayList<>();
        Matcher m = PUNCT_SPLIT.matcher(token);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) {
                out.add(token.substring(last, m.start()));
            }
            out.add(m.group());
            last = m.end();
        }
        if (last < token.length()) {
            out.add(token.substring(last));
        }
        return out;
    }

    List<Integer> wordPiece(String word) {
        if (word == null || word.isEmpty()) return List.of();
        if (vocab.containsKey(word)) {
            return List.of(vocab.get(word));
        }
        List<Integer> ids = new ArrayList<>();
        int start = 0;
        while (start < word.length()) {
            int end = word.length();
            Integer cur = null;
            while (start < end) {
                String sub = word.substring(start, end);
                if (start > 0) sub = "##" + sub;
                Integer id = vocab.get(sub);
                if (id != null) {
                    cur = id;
                    break;
                }
                end--;
            }
            if (cur == null) {
                return List.of(UNK_ID);
            }
            ids.add(cur);
            start = end;
        }
        return ids;
    }

    public static final class Encoded {
        public final long[] inputIds;
        public final long[] attentionMask;
        public final long[] tokenTypeIds;

        Encoded(long[] inputIds, long[] attentionMask, long[] tokenTypeIds) {
            this.inputIds = inputIds;
            this.attentionMask = attentionMask;
            this.tokenTypeIds = tokenTypeIds;
        }
    }
}
