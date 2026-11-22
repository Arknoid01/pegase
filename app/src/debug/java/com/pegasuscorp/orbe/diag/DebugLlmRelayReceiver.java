package com.pegasuscorp.orbe.diag;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.pegasuscorp.orbe.chat.ApiKeyStore;
import com.pegasuscorp.orbe.chat.ProviderChain;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * DEBUG UNIQUEMENT (src/debug) : relais de requêtes LLM pour les bancs du PC.
 * La clé API reste dans le téléphone : le PC dépose des requêtes, Orbe appelle Groq avec SA clé
 * et écrit les réponses (jamais la clé).
 *
 * <pre>
 * adb push req.jsonl /data/local/tmp/relay_req.jsonl   (une ligne = corps OpenAI chat/completions + "id")
 * adb shell am broadcast -n com.pegasuscorp.orbe/.diag.DebugLlmRelayReceiver
 * adb exec-out run-as com.pegasuscorp.orbe cat files/relay/resp.jsonl   (puis files/relay/done)
 * </pre>
 */
public class DebugLlmRelayReceiver extends BroadcastReceiver {

    private static final String TAG = "PegaseRelay";
    /** Un nouveau lot annule le précédent (un seul relais à la fois). */
    private static final java.util.concurrent.atomic.AtomicInteger GEN =
            new java.util.concurrent.atomic.AtomicInteger();

    @Override
    public void onReceive(Context context, Intent intent) {
        final Context app = context.getApplicationContext();
        final String in = intent.getStringExtra("in") != null
                ? intent.getStringExtra("in") : "/data/local/tmp/relay_req.jsonl";
        // Pas de goAsync() : un récepteur retenu plus de ~60 s déclenche « l'appli ne répond pas ».
        // Le fil de fond vit tant que le processus d'Orbe (launcher) est là.
        final int gen = GEN.incrementAndGet();
        new Thread(() -> {
            try {
                run(app, new File(in), gen);
            } catch (Exception e) {
                Log.e(TAG, "relais", e);
            }
        }, "llm-relay").start();
    }

    private static void run(Context app, File in, int gen) throws Exception {
        String key = ApiKeyStore.getGroqKey(app);
        File dir = new File(app.getFilesDir(), "relay");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File out = new File(dir, "resp.jsonl");
        File done = new File(dir, "done");
        //noinspection ResultOfMethodCallIgnored
        done.delete();
        if (key == null || key.isEmpty()) {
            write(out, new JSONObject().put("erreur", "pas de clé Groq dans Orbe").toString() + "\n", false);
            write(done, "1", false);
            return;
        }
        write(out, "", false);
        int n = 0;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(in), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (gen != GEN.get()) {
                    Log.i(TAG, "lot " + gen + " abandonné (nouveau lot)");
                    return;
                }
                if (line.trim().isEmpty()) continue;
                JSONObject req = new JSONObject(line);
                String id = req.optString("id", String.valueOf(n));
                req.remove("id");
                JSONObject res = new JSONObject().put("id", id);
                long t0 = System.currentTimeMillis();
                try {
                    res.put("reponse", call(key, req));
                } catch (Exception e) {
                    res.put("erreur", e.getMessage());
                }
                res.put("ms", System.currentTimeMillis() - t0);
                write(out, res.toString() + "\n", true);
                n++;
                Log.i(TAG, "requête " + n + " (" + id + ")");
            }
        }
        write(done, String.valueOf(n), false);
        Log.i(TAG, "terminé : " + n + " requêtes");
    }

    /** POST Groq avec reprise sur quota (429). Renvoie le texte de la réponse. */
    private static String call(String key, JSONObject body) throws Exception {
        for (int attempt = 0; attempt < 6; attempt++) {
            HttpURLConnection c = (HttpURLConnection) new URL(ProviderChain.GROQ_URL).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(10_000);
            c.setReadTimeout(60_000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Authorization", "Bearer " + key);
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            if (code == 429) {
                String ra = c.getHeaderField("retry-after");
                long wait = 6000;
                try { if (ra != null) wait = (long) (Double.parseDouble(ra) * 1000) + 500; } catch (Exception ignored) {}
                c.disconnect();
                Thread.sleep(Math.min(wait, 60_000));
                continue;
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(
                    code >= 400 ? c.getErrorStream() : c.getInputStream(), StandardCharsets.UTF_8))) {
                String l;
                while ((l = br.readLine()) != null) sb.append(l);
            }
            c.disconnect();
            if (code >= 400) throw new IllegalStateException("HTTP " + code + " " + sb.substring(0, Math.min(200, sb.length())));
            return new JSONObject(sb.toString()).getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").optString("content", "").trim();
        }
        throw new IllegalStateException("quota Groq dépassé");
    }

    private static void write(File f, String s, boolean append) throws Exception {
        try (FileOutputStream fos = new FileOutputStream(f, append)) {
            fos.write(s.getBytes(StandardCharsets.UTF_8));
        }
    }
}
