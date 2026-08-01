package com.pegasuscorp.orbe.voice;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

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
     * Chaque token doit exister dans tokens.txt — sinon Sherpa abort natif.
     * Modèle GigaSpeech EN — « Pégase » FR : score haut + seuil bas.
     */
    static final String KEYWORDS_CONTENT =
            "\u2581P E G AS E :6.0 #0.05 @PEGASE\n"
                    + "\u2581P E G A Z :6.0 #0.05 @PEGAZ\n"
                    + "\u2581P E G AS :5.0 #0.05 @PEGAS\n"
                    + "\u2581P E G A S E :5.0 #0.05 @PEGASE_CHARS\n"
                    + "\u2581P E G A SE :5.5 #0.05 @PEGA_SE\n"
                    + "\u2581P E G AS US :5.0 #0.05 @PEGASUS\n"
                    + "\u2581HE Y \u2581P E G AS E :7.0 #0.04 @HEY_PEGASE\n"
                    + "\u2581O K \u2581P E G AS E :6.0 #0.05 @OK_PEGASE\n"
                    + "\u2581BO N J O UR \u2581P E G AS E :5.0 #0.05 @BONJOUR_PEGASE\n"
                    + "\u2581HE Y \u2581P E G AS US :6.0 #0.05 @HEY_PEGASUS\n";

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

    static void writeKeywords(Context context) throws Exception {
        File f = keywordsFile(context);
        File parent = f.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
            out.write(KEYWORDS_CONTENT.getBytes(StandardCharsets.UTF_8));
        }
        Log.i(TAG, "keywords written bytes=" + KEYWORDS_CONTENT.length()
                + " path=" + f.getAbsolutePath());
    }

    /** Réécrit keywords.txt à chaque start KWS (boosts / variantes à jour). */
    public static void ensureKeywords(Context context) {
        try {
            writeKeywords(context);
        } catch (Exception e) {
            Log.w(TAG, "ensureKeywords failed", e);
        }
    }
}
