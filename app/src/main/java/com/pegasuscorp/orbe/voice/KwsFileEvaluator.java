package com.pegasuscorp.orbe.voice;

import android.content.Context;
import android.util.Log;

import com.k2fsa.sherpa.onnx.KeywordSpotter;
import com.k2fsa.sherpa.onnx.KeywordSpotterResult;
import com.k2fsa.sherpa.onnx.OnlineStream;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Évaluation hors ligne du mot d'éveil sur un fichier WAV.
 *
 * <p>Parler au téléphone fait varier le volume, la distance et l'humeur du lien à
 * chaque essai : on ne compare jamais deux fois la même épreuve. Ici le moteur reçoit
 * toujours le même son, ce qui permet d'isoler une variable à la fois — typiquement
 * « ce modèle sait-il reconnaître la phrase en qualité Bluetooth propre ? ».
 *
 * <p>Usage : déposer des {@code kws_eval_*.wav} (PCM 16 bits mono 16 kHz) dans
 * {@code files/diag/}, puis relancer le service. Chaque fichier est joué une fois,
 * le résultat part dans le log JSONL, et le fichier est renommé en {@code .done}.
 */
final class KwsFileEvaluator {

    private static final String TAG = "KwsFileEval";
    /** Taille des blocs poussés dans le moteur — équivalent de 100 ms de capture. */
    private static final int CHUNK = 1_600;

    private KwsFileEvaluator() {}

    /** @return true si au moins un fichier a été évalué. */
    static boolean runPending(Context app, KeywordSpotter kws, int sampleRate) {
        if (kws == null) return false;
        File dir = new File(app.getFilesDir(), "diag");
        File[] files = dir.listFiles((d, name) ->
                name.startsWith("kws_eval_") && name.endsWith(".wav"));
        if (files == null || files.length == 0) return false;
        java.util.Arrays.sort(files);
        for (File f : files) {
            try {
                evaluate(app, kws, sampleRate, f);
            } catch (Exception e) {
                Log.w(TAG, "évaluation " + f.getName(), e);
            }
            // Renommer même en cas d'échec : ne pas rejouer en boucle au redémarrage.
            File done = new File(f.getParentFile(), f.getName() + ".done");
            if (done.exists() && !done.delete()) Log.w(TAG, "delete " + done.getName());
            if (!f.renameTo(done)) Log.w(TAG, "rename " + f.getName());
        }
        return true;
    }

    private static void evaluate(Context app, KeywordSpotter kws, int sampleRate, File f)
            throws Exception {
        float[] pcm = readWavMono16(f);
        if (pcm.length == 0) {
            report(app, f.getName(), "", "", 0, "wav_vide");
            return;
        }
        OnlineStream stream = kws.createStream();
        if (stream == null || stream.getPtr() == 0L) {
            report(app, f.getName(), "", "", pcm.length, "stream_null");
            return;
        }
        String hit = "";
        StringBuilder allTokens = new StringBuilder();
        try {
            for (int off = 0; off < pcm.length; off += CHUNK) {
                int n = Math.min(CHUNK, pcm.length - off);
                float[] block = new float[n];
                System.arraycopy(pcm, off, block, 0, n);
                stream.acceptWaveform(block, sampleRate);
                while (kws.isReady(stream)) {
                    kws.decode(stream);
                    KeywordSpotterResult r = kws.getResult(stream);
                    if (r == null) continue;
                    String[] tk = r.getTokens();
                    if (tk != null && tk.length > 0) {
                        if (allTokens.length() > 0) allTokens.append(' ');
                        allTokens.append(java.util.Arrays.toString(tk));
                    }
                    String kw = r.getKeyword();
                    if (kw != null && !kw.trim().isEmpty() && hit.isEmpty()) {
                        hit = kw.trim();
                        kws.reset(stream);
                    }
                }
            }
        } finally {
            try { stream.release(); } catch (Exception ignored) {}
        }
        String tokens = allTokens.length() > 32000
                ? allTokens.substring(0, 32000) : allTokens.toString();
        Log.i(TAG, f.getName() + " → hit=" + (hit.isEmpty() ? "AUCUN" : hit)
                + " tokens=" + (tokens.isEmpty() ? "[]" : tokens));
        report(app, f.getName(), hit, tokens, pcm.length, "");
    }

    private static void report(Context app, String name, String hit, String tokens,
                               int samples, String error) {
        try {
            JSONObject f = new JSONObject();
            f.put("file", name);
            f.put("hit", hit);
            f.put("has_tokens", !tokens.isEmpty());
            f.put("tokens", tokens.length() > 400 ? tokens.substring(0, 400) : tokens);
            f.put("samples", samples);
            if (!error.isEmpty()) f.put("error", error);
            com.pegasuscorp.orbe.diag.PegaseDiagLog.kws(app, "kws_eval_result", f);
        } catch (Exception ignored) {}
    }

    /** Lecteur WAV minimal : PCM 16 bits mono, en cherchant le chunk {@code data}. */
    private static float[] readWavMono16(File f) throws Exception {
        byte[] all;
        try (FileInputStream in = new FileInputStream(f)) {
            all = new byte[(int) f.length()];
            int read = 0;
            while (read < all.length) {
                int n = in.read(all, read, all.length - read);
                if (n <= 0) break;
                read += n;
            }
        }
        if (all.length < 44) return new float[0];
        ByteBuffer bb = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN);
        int pos = 12; // saute RIFF____WAVE
        int dataOff = -1, dataLen = 0;
        while (pos + 8 <= all.length) {
            int id = bb.getInt(pos);
            int size = bb.getInt(pos + 4);
            if (id == 0x61746164) { // "data"
                dataOff = pos + 8;
                dataLen = Math.min(size, all.length - dataOff);
                break;
            }
            pos += 8 + size + (size & 1);
        }
        if (dataOff < 0 || dataLen <= 0) return new float[0];
        int n = dataLen / 2;
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            out[i] = bb.getShort(dataOff + i * 2) / 32768f;
        }
        return out;
    }
}
