package com.pegasuscorp.orbe.diag;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Debug;
import android.util.Log;

import com.pegasuscorp.orbe.rag.VectorMath;
import com.pegasuscorp.orbe.search.UnigramTokenizer;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

/**
 * DEBUG UNIQUEMENT : banc d'un modèle d'empreintes de phrases (MiniLM, mpnet…) sur le téléphone,
 * pour le chatbot hybride. Mesure chargement, mémoire, temps par phrase et encodage d'une banque.
 *
 * <pre>
 * adb shell am broadcast -n com.pegasuscorp.orbe/.diag.EmbedBenchReceiver \
 *     --es dir /sdcard/Android/data/com.pegasuscorp.orbe/files/models/mpnet-int8 --es model model_int8.onnx
 * adb logcat -s EmbedBench
 * </pre>
 * Le dossier contient le modèle ONNX et tokenizer.json. Résultat : lignes « EmbedBench ».
 */
public class EmbedBenchReceiver extends BroadcastReceiver {

    private static final String TAG = "EmbedBench";

    /** Phrases de test génériques (style SMS), sans donnée perso. */
    private static final String[] PHRASES = {
            "coucou ça va ?", "je suis crevé ce soir", "journée de fou au boulot aujourd'hui",
            "j'ai eu une idée de jeu cette nuit", "ce bug me rend dingue depuis ce matin",
            "tu me conseilles quoi comme film ce soir ?", "raconte-moi une blague",
            "j'ai peur de me planter avec mon projet", "il va pleuvoir demain ?",
            "mets un minuteur de dix minutes", "les deux Ferrari se sont encore accrochées",
            "un jour j'irai voir un grand prix en vrai", "je sais plus par quoi commencer",
            "un ami est passé m'aider ce matin", "c'est sûrement mon dernier Noël à la boutique",
            "je voudrais virer toutes les applis Google de mon téléphone", "et toi ta journée ?",
            "bon il est une heure du matin je devrais aller dormir", "ça marche enfin, je suis trop content",
            "tu peux me raconter une histoire de pirates ?",
    };

    @Override
    public void onReceive(Context context, Intent intent) {
        final String dir = intent.getStringExtra("dir");
        final String model = intent.getStringExtra("model");
        final int threads = intent.getIntExtra("threads", 4);
        final int bank = intent.getIntExtra("bank", 320);
        if (dir == null || model == null) {
            Log.w(TAG, "extras « dir » et « model » requis");
            return;
        }
        final PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                run(new File(dir), model, threads, bank);
            } catch (Throwable t) {
                Log.e(TAG, "ECHEC", t);
            } finally {
                pending.finish();
            }
        }, "embed-bench").start();
    }

    private static long rssKb() {
        try {
            for (String l : Files.readAllLines(new File("/proc/self/status").toPath())) {
                if (l.startsWith("VmRSS:")) return Long.parseLong(l.replaceAll("\\D+", ""));
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    private static void run(File dir, String modelFile, int threads, int bankSize) throws Exception {
        System.gc();
        long rss0 = rssKb(), pss0 = Debug.getPss();
        long t0 = System.nanoTime();
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setIntraOpNumThreads(threads);
        OrtSession session = env.createSession(new File(dir, modelFile).getAbsolutePath(), opts);
        UnigramTokenizer tok = UnigramTokenizer.loadCached(new File(dir, "tokenizer.json"));
        long loadMs = (System.nanoTime() - t0) / 1_000_000;
        Set<String> inputs = session.getInputNames();
        long rss1 = rssKb(), pss1 = Debug.getPss();
        Log.i(TAG, String.format("modèle %s (%d Mo) : chargé en %d ms, %d fils, entrées %s",
                modelFile, new File(dir, modelFile).length() / 1_000_000, loadMs, threads, inputs));
        Log.i(TAG, String.format("mémoire après chargement : RSS +%d Mo, PSS +%d Mo",
                (rss1 - rss0) / 1024, (pss1 - pss0) / 1024));

        // Préchauffage, puis une phrase à la fois (le cas réel : le message de Yannick).
        embed(env, session, tok, inputs, Arrays.asList(PHRASES[0]));
        long[] lat = new long[PHRASES.length];
        for (int i = 0; i < PHRASES.length; i++) {
            long s = System.nanoTime();
            embed(env, session, tok, inputs, Arrays.asList(PHRASES[i]));
            lat[i] = (System.nanoTime() - s) / 1_000_000;
        }
        long[] sorted = lat.clone();
        Arrays.sort(sorted);
        Log.i(TAG, String.format("une phrase : médiane %d ms, min %d ms, max %d ms",
                sorted[sorted.length / 2], sorted[0], sorted[sorted.length - 1]));

        // Encodage d'une banque entière (ce que le PC fera à l'avance ; ici pour l'ordre de grandeur).
        List<String> b = new ArrayList<>();
        for (int i = 0; i < bankSize; i++) b.add(PHRASES[i % PHRASES.length] + " " + i);
        long s = System.nanoTime();
        for (int k = 0; k < b.size(); k += 16) {
            embed(env, session, tok, inputs, b.subList(k, Math.min(b.size(), k + 16)));
        }
        long bankMs = (System.nanoTime() - s) / 1_000_000;
        long rss2 = rssKb(), pss2 = Debug.getPss();
        Log.i(TAG, String.format("banque de %d phrases : %d ms ; mémoire en pointe : RSS +%d Mo, PSS +%d Mo",
                bankSize, bankMs, (rss2 - rss0) / 1024, (pss2 - pss0) / 1024));
        session.close();
        Log.i(TAG, "FIN");
    }

    private static float[][] embed(OrtEnvironment env, OrtSession session, UnigramTokenizer tok,
                                   Set<String> inputs, List<String> texts) throws Exception {
        long[][] enc = new long[texts.size()][];
        int maxLen = 1;
        for (int i = 0; i < texts.size(); i++) {
            enc[i] = tok.encode(texts.get(i));
            maxLen = Math.max(maxLen, enc[i].length);
        }
        long[][] ids = new long[enc.length][maxLen];
        long[][] mask = new long[enc.length][maxLen];
        for (int r = 0; r < enc.length; r++) {
            for (int c = 0; c < maxLen; c++) {
                boolean in = c < enc[r].length;
                ids[r][c] = in ? enc[r][c] : tok.padId();
                mask[r][c] = in ? 1 : 0;
            }
        }
        Map<String, OnnxTensor> feed = new HashMap<>();
        List<OnnxTensor> owned = new ArrayList<>();
        try {
            OnnxTensor tIds = OnnxTensor.createTensor(env, ids);
            OnnxTensor tMask = OnnxTensor.createTensor(env, mask);
            owned.add(tIds);
            owned.add(tMask);
            feed.put("input_ids", tIds);
            feed.put("attention_mask", tMask);
            if (inputs.contains("token_type_ids")) {   // MiniLM oui, mpnet non
                OnnxTensor tTypes = OnnxTensor.createTensor(env, new long[enc.length][maxLen]);
                owned.add(tTypes);
                feed.put("token_type_ids", tTypes);
            }
            try (OrtSession.Result res = session.run(feed)) {
                float[][][] hidden = (float[][][]) res.get(0).getValue();
                float[][] out = new float[enc.length][];
                for (int r = 0; r < enc.length; r++) {
                    out[r] = VectorMath.l2Normalize(VectorMath.meanPool(hidden[r], mask[r]));
                }
                return out;
            }
        } finally {
            for (OnnxTensor t : owned) t.close();
        }
    }
}
