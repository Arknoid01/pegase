package com.pegasuscorp.orbe.chatbot;

import android.content.Context;
import android.util.Log;

import com.pegasuscorp.orbe.search.MultilingualEmbedder;

import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Charge le chatbot hybride sur le téléphone.
 *
 * <ul>
 *   <li>règles génériques : assets/chatbot/rules.json (dans l'APK) ;</li>
 *   <li>banque et fiches PERSONNELLES : Android/data/…/files/chatbot/{banque,fiches}.json,
 *       poussées à la main (adb), jamais dans le dépôt ni l'APK ;</li>
 *   <li>modèle d'empreintes : Android/data/…/files/models/mpnet-int8 (model_int8.onnx +
 *       tokenizer.json) — mesuré 11-12 ms par phrase, +200-255 Mo de RAM (2026-11-25).</li>
 * </ul>
 *
 * Sans banque ni fiches : pas de chatbot ({@link #get} rend null), Orbe répond comme avant.
 * Le modèle est libéré après {@link #IDLE_MS} sans message (rechargé en ≈1-2 s).
 */
public final class ChatbotStore {

    private static final String TAG = "Chatbot";
    public static final String MODEL_DIR = "mpnet-int8";
    public static final String MODEL_FILE = "model_int8.onnx";
    static final long IDLE_MS = 5 * 60_000L;

    private static ChatbotStore instance;

    private final Context app;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> release;
    private MultilingualEmbedder embedder;
    private ChatbotEngine engine;
    private long dataStamp = -1;

    private ChatbotStore(Context ctx) {
        app = ctx.getApplicationContext();
    }

    public static synchronized ChatbotStore of(Context ctx) {
        if (instance == null) instance = new ChatbotStore(ctx);
        return instance;
    }

    public static File dataDir(Context ctx) {
        File ext = ctx.getApplicationContext().getExternalFilesDir("chatbot");
        return ext != null ? ext : new File(ctx.getFilesDir(), "chatbot");
    }

    public static File modelDir(Context ctx) {
        File ext = ctx.getApplicationContext().getExternalFilesDir("models");
        File base = ext != null ? ext : new File(ctx.getFilesDir(), "models");
        return new File(base, MODEL_DIR);
    }

    public boolean installed() {
        File d = dataDir(app), m = modelDir(app);
        return new File(d, "banque.json").isFile() && new File(d, "fiches.json").isFile()
                && new File(m, MODEL_FILE).isFile() && new File(m, "tokenizer.json").isFile();
    }

    /**
     * Le moteur prêt (chargé au besoin, rechargé si banque/fiches ont changé), ou null si le
     * chatbot n'est pas installé ou n'a pas pu se charger. Appelé hors du fil principal.
     */
    public synchronized ChatbotEngine get() {
        if (!installed()) return null;
        try {
            File d = dataDir(app);
            File bank = new File(d, "banque.json"), fiches = new File(d, "fiches.json");
            long stamp = bank.lastModified() ^ (fiches.lastModified() * 31) ^ bank.length();
            if (engine == null || stamp != dataStamp) {
                long t0 = System.currentTimeMillis();
                JSONObject rules = new JSONObject(readAsset("chatbot/rules.json"));
                ChatbotEngine e = new ChatbotEngine(rules, new JSONObject(read(bank)),
                        new Fiches(new JSONObject(read(fiches))), this::embed, true, null);
                File cache = new File(app.getFilesDir(), "chatbot-vectors.bin");
                long hash = textsHash(e.bankTexts());
                float[][] cached = readVectors(cache, hash);
                e.loadVectors(cached);
                if (cached == null) writeVectors(cache, hash, e.vectors());
                engine = e;
                dataStamp = stamp;
                Log.i(TAG, "chargé : " + e.bankTexts().size() + " formulations, empreintes "
                        + (cached != null ? "du cache" : "calculées") + ", "
                        + (System.currentTimeMillis() - t0) + " ms");
            }
            return engine;
        } catch (Throwable t) {
            Log.w(TAG, "chargement impossible", t);
            return null;
        }
    }

    private synchronized float[][] embed(List<String> texts) throws Exception {
        if (embedder == null) {
            long t0 = System.currentTimeMillis();
            embedder = MultilingualEmbedder.open(modelDir(app), MODEL_FILE);
            Log.i(TAG, "modèle d'empreintes chargé en " + (System.currentTimeMillis() - t0) + " ms");
        }
        if (release != null) release.cancel(false);
        release = timer.schedule(this::releaseModel, IDLE_MS, TimeUnit.MILLISECONDS);
        return embedder.embed(texts);
    }

    private synchronized void releaseModel() {
        if (embedder == null) return;
        try {
            embedder.close();
        } catch (Exception ignored) {
        }
        embedder = null;
        Log.i(TAG, "modèle d'empreintes libéré (inactif)");
    }

    // ------------------------------------------------------------ fichiers

    private String readAsset(String path) throws Exception {
        try (InputStream in = app.getAssets().open(path)) {
            return new String(readAll(in), StandardCharsets.UTF_8);
        }
    }

    private static String read(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    private static byte[] readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    static long textsHash(List<String> texts) {
        long h = 1125899906842597L ^ MODEL_FILE.hashCode();
        for (String t : texts) h = 31 * h + t.hashCode();
        return h;
    }

    static float[][] readVectors(File f, long hash) {
        if (!f.isFile()) return null;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f)))) {
            if (in.readLong() != hash) return null;
            int n = in.readInt(), dim = in.readInt();
            float[][] v = new float[n][dim];
            for (int i = 0; i < n; i++) for (int j = 0; j < dim; j++) v[i][j] = in.readFloat();
            return v;
        } catch (Exception e) {
            return null;
        }
    }

    static void writeVectors(File f, long hash, float[][] v) {
        if (v == null || v.length == 0) return;
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(f)))) {
            out.writeLong(hash);
            out.writeInt(v.length);
            out.writeInt(v[0].length);
            for (float[] row : v) for (float x : row) out.writeFloat(x);
        } catch (Exception e) {
            Log.w(TAG, "cache des empreintes non écrit", e);
        }
    }
}
