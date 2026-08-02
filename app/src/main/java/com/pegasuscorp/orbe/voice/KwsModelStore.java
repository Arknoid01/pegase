package com.pegasuscorp.orbe.voice;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Modèle Sherpa-onnx keyword spotting (GigaSpeech 3.3M fp32) pour le wake « Pégase ».
 * Fichiers sous {@code files/kws/} — process {@code :voice} et launcher partagent le même
 * {@code filesDir} (UID unique), donc le téléchargement depuis l'UI suffit.
 */
public final class KwsModelStore {

    private static final String TAG = "KwsModelStore";

    static final String ARCHIVE_NAME =
            "sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2";
    static final String ARCHIVE_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/"
                    + ARCHIVE_NAME;

    /** Pack fp32 (pas le mobile int8 — Reshape 17≠16 avec l'AAR xdcobra). */
    static final String ENCODER = "encoder-epoch-12-avg-2-chunk-16-left-64.onnx";
    static final String DECODER = "decoder-epoch-12-avg-2-chunk-16-left-64.onnx";
    static final String JOINER = "joiner-epoch-12-avg-2-chunk-16-left-64.onnx";
    static final String TOKENS = "tokens.txt";
    static final String KEYWORDS = "keywords.txt";

    /** Tailles mini pour rejeter un mauvais fichier / reste de cache. */
    private static final long MIN_ENCODER_BYTES = 8_000_000L; // fp32 ≫ int8 (~4 Mo)
    private static final long MIN_DECODER_BYTES = 100_000L;
    private static final long MIN_JOINER_BYTES = 100_000L;
    private static final long MIN_TOKENS_BYTES = 100L;
    private static final long MIN_KEYWORDS_BYTES = 10L;

    /**
     * Lignes BPE (▁ = U+2581) + boost Sherpa {@code :score #threshold @id}.
     * Alias courts gardés pour le rappel FR (modèle EN) — filtrés ensuite par
     * gate RMS + anti-poche côté {@link SherpaKwsEngine}.
     * Validé à l'écriture via {@link #ensureKeywords}.
     */
    static final String KEYWORDS_CONTENT =
            "\u2581P E G AS E :7.0 #0.04 @PEGASE\n"
                    + "\u2581P E G A Z E :7.0 #0.04 @PEGAZE\n"
                    + "\u2581P E G A Z :6.5 #0.05 @PEGAZ\n"
                    + "\u2581P E G AS :6.0 #0.05 @PEGAS\n"
                    + "\u2581P E G A S E :6.0 #0.04 @PEGASE_CHARS\n"
                    + "\u2581P E G A SE :6.5 #0.04 @PEGA_SE\n"
                    + "\u2581P E G AS US :6.0 #0.05 @PEGASUS\n"
                    // Composés plus stricts : évite un HIT « HEY_PEGASE » collé sur une discussion.
                    + "\u2581HE Y \u2581P E G AS E :6.0 #0.10 @HEY_PEGASE\n"
                    + "\u2581O K \u2581P E G AS E :5.5 #0.10 @OK_PEGASE\n"
                    + "\u2581BO N J O UR \u2581P E G AS E :5.5 #0.12 @BONJOUR_PEGASE\n"
                    + "\u2581HE Y \u2581P E G AS US :6.0 #0.10 @HEY_PEGASUS\n";

    private KwsModelStore() {}

    public static File modelDir(Context context) {
        File dir = new File(context.getApplicationContext().getFilesDir(), "kws");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public static File encoderFile(Context context) {
        return new File(modelDir(context), ENCODER);
    }

    public static File decoderFile(Context context) {
        return new File(modelDir(context), DECODER);
    }

    public static File joinerFile(Context context) {
        return new File(modelDir(context), JOINER);
    }

    public static File tokensFile(Context context) {
        return new File(modelDir(context), TOKENS);
    }

    public static File keywordsFile(Context context) {
        return new File(modelDir(context), KEYWORDS);
    }

    public static boolean isModelReady(Context context) {
        return encoderFile(context).isFile() && encoderFile(context).length() >= MIN_ENCODER_BYTES
                && decoderFile(context).isFile() && decoderFile(context).length() >= MIN_DECODER_BYTES
                && joinerFile(context).isFile() && joinerFile(context).length() >= MIN_JOINER_BYTES
                && tokensFile(context).isFile() && tokensFile(context).length() >= MIN_TOKENS_BYTES
                && keywordsFile(context).isFile() && keywordsFile(context).length() >= MIN_KEYWORDS_BYTES;
    }

    /**
     * Log chemin / taille / sha256[0:12] — détecte mauvais asset ou cache obsolète.
     */
    static void logModelIdentity(Context context) {
        File dir = modelDir(context);
        Log.i(TAG, "kws dir=" + dir.getAbsolutePath()
                + " ready=" + isModelReady(context));
        logOne(encoderFile(context), MIN_ENCODER_BYTES);
        logOne(decoderFile(context), MIN_DECODER_BYTES);
        logOne(joinerFile(context), MIN_JOINER_BYTES);
        logOne(tokensFile(context), MIN_TOKENS_BYTES);
        logOne(keywordsFile(context), MIN_KEYWORDS_BYTES);
        File staleInt8 = new File(dir, "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx");
        if (staleInt8.isFile()) {
            Log.w(TAG, "STALE int8 still present: " + staleInt8.getName()
                    + " size=" + staleInt8.length() + " (ignored by loader)");
        }
    }

    private static void logOne(File f, long minBytes) {
        if (!f.isFile()) {
            Log.w(TAG, "MISSING " + f.getName() + " path=" + f.getAbsolutePath());
            return;
        }
        long len = f.length();
        String sha = sha256Prefix(f, 12);
        boolean ok = len >= minBytes;
        Log.i(TAG, (ok ? "OK " : "BAD_SIZE ") + f.getName()
                + " path=" + f.getAbsolutePath()
                + " size=" + len
                + " min=" + minBytes
                + " sha256[0:12]=" + sha);
    }

    private static String sha256Prefix(File f, int hexChars) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            try (FileInputStream in = new FileInputStream(f)) {
                int n;
                while ((n = in.read(buf)) >= 0) {
                    if (n > 0) md.update(buf, 0, n);
                }
            }
            byte[] dig = md.digest();
            StringBuilder sb = new StringBuilder(hexChars);
            for (int i = 0; i < dig.length && sb.length() < hexChars; i++) {
                sb.append(String.format(Locale.US, "%02x", dig[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return "?";
        }
    }

    public static String statusLabel(Context context) {
        return isModelReady(context)
                ? "Wake local Sherpa installé"
                : "Wake local Sherpa non installé (~17 Mo)";
    }

    /**
     * Filtre les lignes dont chaque pièce BPE existe dans tokens.txt.
     * Évite l'abort natif Sherpa (ex. ▁PE inexistant).
     */
    static String filterValidKeywordLines(String content, Set<String> vocab) {
        if (content == null || content.isEmpty()) return "";
        if (vocab == null || vocab.isEmpty()) return content;
        StringBuilder out = new StringBuilder();
        int kept = 0;
        int dropped = 0;
        for (String raw : content.split("\n", -1)) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            String missing = firstMissingToken(line, vocab);
            if (missing != null) {
                dropped++;
                logSafe("keywords line dropped — missing token \"" + missing + "\": " + line);
                continue;
            }
            out.append(line).append('\n');
            kept++;
        }
        logSafe("keywords validated kept=" + kept + " dropped=" + dropped);
        return out.toString();
    }

    /** JVM unit tests n'ont pas android.util.Log mocké. */
    private static void logSafe(String msg) {
        try {
            Log.w(TAG, msg);
        } catch (RuntimeException ignored) {}
    }

    /** @return premier token absent, ou null si la ligne est valide. */
    static String firstMissingToken(String line, Set<String> vocab) {
        if (line == null || vocab == null) return "null";
        for (String piece : line.trim().split("\\s+")) {
            if (piece.isEmpty()) continue;
            char c0 = piece.charAt(0);
            if (c0 == ':' || c0 == '#' || c0 == '@') break;
            if (!vocab.contains(piece)) return piece;
        }
        return null;
    }

    static Set<String> loadTokenVocab(Context context) {
        Set<String> vocab = new HashSet<>();
        File f = tokensFile(context);
        if (!f.isFile()) return vocab;
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                int sp = line.indexOf(' ');
                String tok = sp > 0 ? line.substring(0, sp) : line;
                if (!tok.isEmpty()) vocab.add(tok);
            }
        } catch (Exception e) {
            Log.w(TAG, "loadTokenVocab failed", e);
        }
        return vocab;
    }

    static void writeKeywords(Context context) throws Exception {
        Set<String> vocab = loadTokenVocab(context);
        String filtered = filterValidKeywordLines(KEYWORDS_CONTENT, vocab);
        if (filtered.isEmpty()) {
            throw new IllegalStateException("no valid keyword lines after tokens.txt filter");
        }
        File f = keywordsFile(context);
        File parent = f.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        byte[] bytes = filtered.getBytes(StandardCharsets.UTF_8);
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
            out.write(bytes);
        }
        Log.i(TAG, "keywords written bytes=" + bytes.length
                + " path=" + f.getAbsolutePath());
    }

    /** Réécrit keywords.txt à chaque start KWS (boosts / variantes à jour + validation). */
    public static void ensureKeywords(Context context) {
        try {
            writeKeywords(context);
        } catch (Exception e) {
            Log.w(TAG, "ensureKeywords failed", e);
        }
    }
}
