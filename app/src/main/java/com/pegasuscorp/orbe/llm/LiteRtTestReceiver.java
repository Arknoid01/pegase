package com.pegasuscorp.orbe.llm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import org.json.JSONObject;

/**
 * Test manuel de LiteRT-LM (debug uniquement) :
 * {@code adb shell am broadcast -n com.pegasuscorp.orbe/.llm.LiteRtTestReceiver -a com.pegasuscorp.orbe.LITERT_TEST --es prompt "..."}
 * Résultat dans logcat (tag PegaseLiteRT, ligne RESULT).
 */
public final class LiteRtTestReceiver extends BroadcastReceiver {

    private static final String TAG = "PegaseLiteRT";
    static final String ACTION = "com.pegasuscorp.orbe.LITERT_TEST";
    static final String ACTION_CASES = "com.pegasuscorp.orbe.LITERT_CASES";
    static final String ACTION_ROUTE = "com.pegasuscorp.orbe.LITERT_ROUTE";
    static final String ACTION_ROUTES = "com.pegasuscorp.orbe.LITERT_ROUTES";
    static final String ROUTES_FILE = "/data/local/tmp/litert/routes.tsv";
    static final String ACTION_SET = "com.pegasuscorp.orbe.LITERT_SET";
    static final String ACTION_CHAT = "com.pegasuscorp.orbe.LITERT_CHAT";
    static final String ACTION_HALLU = "com.pegasuscorp.orbe.CHATBOT_HALLU";
    static final String HALLU_FILE = "/data/local/tmp/litert/hallu_cases.txt";
    static final String CASES_FILE = "/data/local/tmp/litert/cases_fixed.tsv";
    /** Emplacement de test du modèle (poussé avec adb, lisible par l'app en debug). */
    static final String TEST_MODEL = "/data/local/tmp/litert/gemma-4-E2B-it.litertlm";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if ((ctx.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0
                || intent == null) return;
        if (ACTION_ROUTE.equals(intent.getAction())) {
            final Context app = ctx.getApplicationContext();
            final String text = intent.getStringExtra("text");
            final String toolsArg = intent.getStringExtra("tools");
            new Thread(() -> runRoute(app, text, toolsArg), "litert-route").start();
            return;
        }
        if (ACTION_SET.equals(intent.getAction())) {
            LocalLlmServer.setLitert(ctx, intent.getBooleanExtra("on", true));
            Log.i(TAG, "moteur LiteRT = " + LocalLlmServer.isLitert(ctx));
            return;
        }
        if (ACTION_CHAT.equals(intent.getAction())) {
            final Context app = ctx.getApplicationContext();
            final String text = intent.getStringExtra("text") == null ? "Salut" : intent.getStringExtra("text").replace('_', ' ');
            new Thread(() -> {
                try {
                    JSONObject body = new JSONObject().put("messages", new org.json.JSONArray()
                            .put(new JSONObject().put("role", "system").put("content", "Tu es Pégase. Réponds en français, en 2 phrases."))
                            .put(new JSONObject().put("role", "user").put("content", text)))
                            .put("max_tokens", 200);
                    // Option de test : --ef temperature 0 (glouton) / 0.7 ; absent = défauts du modèle.
                    if (intent.hasExtra("temperature")) body.put("temperature", intent.getFloatExtra("temperature", -1f));
                    long t0 = System.currentTimeMillis();
                    final int[] parts = {0};
                    final long[] firstMs = {-1};
                    LocalLlmServer.get(app).ensureStarted(); // test debug : démarre llama-server si besoin
                    String full = LocalLlmServer.get(app).chatStream(body, 120_000, acc -> {
                        parts[0]++;
                        if (firstMs[0] < 0) firstMs[0] = System.currentTimeMillis() - t0;
                    });
                    Log.i(TAG, "CHAT " + (System.currentTimeMillis() - t0) + " ms, premier morceau "
                            + firstMs[0] + " ms, " + parts[0] + " morceaux : " + full);
                } catch (Throwable t) {
                    Log.e(TAG, "CHAT ECHEC " + t);
                }
            }, "litert-chat").start();
            return;
        }
        if (ACTION_HALLU.equals(intent.getAction())) {
            final Context app = ctx.getApplicationContext();
            final int n = intent.getIntExtra("n", 3);
            new Thread(() -> runHallu(app, n), "chatbot-hallu").start();
            return;
        }
        if (ACTION_ROUTES.equals(intent.getAction())) {
            final Context app = ctx.getApplicationContext();
            new Thread(() -> runRoutesFile(app), "litert-routes").start();
            return;
        }
        if (ACTION_CASES.equals(intent.getAction())) {
            final Context app = ctx.getApplicationContext();
            new Thread(() -> runCases(app), "litert-cases").start();
            return;
        }
        if (!ACTION.equals(intent.getAction())) return;
        // adb coupe les arguments aux espaces : on écrit des « _ » à la place.
        final String rawPrompt = intent.getStringExtra("prompt");
        final String prompt = rawPrompt == null ? null : rawPrompt.replace('_', ' ');
        final String system = intent.getStringExtra("system");
        final String model = intent.getStringExtra("model") != null
                ? intent.getStringExtra("model") : TEST_MODEL;
        final int max = intent.getIntExtra("max", 256);
        final String backend = intent.getStringExtra("backend") != null
                ? intent.getStringExtra("backend") : "gpu";
        final android.content.Context app = ctx.getApplicationContext();
        new Thread(() -> run(app, model, system, prompt, max, backend), "litert-test").start();
    }

    static void run(Context ctx, String model, String system, String prompt, int max) {
        run(ctx, model, system, prompt, max, "gpu");
    }

    static void run(Context ctx, String model, String system, String prompt, int max, String backend) {
        try {
            long t0 = System.currentTimeMillis();
            java.io.File cache = new java.io.File(ctx.getCacheDir(), "litert");
            cache.mkdirs();
            long h = LiteRtNative.nativeLoad(model, cache.getAbsolutePath(), backend);
            if (h == 0) {
                Log.e(TAG, "ECHEC chargement : " + LiteRtNative.lastError());
                return;
            }
            Log.i(TAG, "CHARGEMENT " + (System.currentTimeMillis() - t0) + " ms");
            String sys = system != null ? system : "Tu es Pégase. Réponds en français, en 2 phrases.";
            String userJson = new JSONObject().put("role", "user").put("content", prompt == null ? "Salut" : prompt).toString();
            Log.i(TAG, "ENVOI system=" + sys + " user=" + userJson);
            long t1 = System.currentTimeMillis();
            String json = LiteRtNative.nativeGenerate(h,
                    JSONObject.quote(sys), "[]", userJson,
                    max, null);
            long dt = System.currentTimeMillis() - t1;
            if (json == null) {
                Log.e(TAG, "ECHEC génération : " + LiteRtNative.lastError());
            } else {
                Log.i(TAG, "RESULT " + dt + " ms : " + json);
            }
            LiteRtNative.nativeUnload(h);
        } catch (Throwable t) {
            Log.e(TAG, "ERREUR", t);
        }
    }

    /** Rejoue les cas du journal (fichier TSV : id, système JSON, historique JSON, message JSON). */
    static void runCases(Context ctx) {
        try {
            java.io.File cache = new java.io.File(ctx.getCacheDir(), "litert");
            cache.mkdirs();
            long h = LiteRtNative.nativeLoad(TEST_MODEL, cache.getAbsolutePath(), "gpu");
            if (h == 0) { Log.e(TAG, "ECHEC chargement : " + LiteRtNative.lastError()); return; }
            java.util.List<String> lines = java.nio.file.Files.readAllLines(
                    java.nio.file.Paths.get(CASES_FILE), java.nio.charset.StandardCharsets.UTF_8);
            for (String line : lines) {
                String[] f = line.split("\t", -1);
                if (f.length < 4) continue;
                long t0 = System.currentTimeMillis();
                String json = LiteRtNative.nativeGenerate(h, f[1], f[2], f[3], 256, null);
                long dt = System.currentTimeMillis() - t0;
                String text = "";
                if (json != null) {
                    try {
                        text = new JSONObject(json).getJSONArray("content").getJSONObject(0).getString("text");
                    } catch (Exception e) {
                        text = json;
                    }
                }
                Log.i(TAG, "CASE " + f[0] + " " + dt + " ms : " + (json == null
                        ? "ECHEC " + LiteRtNative.lastError() : text.replace('\n', ' ')));
            }
            LiteRtNative.nativeUnload(h);
            Log.i(TAG, "CASES FIN");
        } catch (Throwable t) {
            Log.e(TAG, "ERREUR cas", t);
        }
    }

    /** Teste le routeur de commandes : contrainte regex LiteRT au lieu de la grammaire GBNF. */
    static void runRoute(Context ctx, String rawText, String toolsArg) {
        try {
            String text = rawText == null ? "" : rawText.replace('_', ' ');
            java.util.List<String> tools = java.util.Arrays.asList(toolsArg.split(","));
            org.json.JSONArray msgs = LocalCommandRouter.compactMessages(tools, text);
            String sys = msgs.getJSONObject(0).getString("content");
            org.json.JSONArray hist = new org.json.JSONArray();
            for (int i = 1; i < msgs.length() - 1; i++) hist.put(msgs.getJSONObject(i));
            String user = msgs.getJSONObject(msgs.length() - 1).toString();
            String regex = LocalCommandRouter.regex(tools);
            java.io.File cache = new java.io.File(ctx.getCacheDir(), "litert");
            cache.mkdirs();
            long h = LiteRtNative.nativeLoad(TEST_MODEL, cache.getAbsolutePath(), "gpu");
            if (h == 0) { Log.e(TAG, "ECHEC chargement : " + LiteRtNative.lastError()); return; }
            long t0 = System.currentTimeMillis();
            String json = LiteRtNative.nativeGenerate(h, JSONObject.quote(sys), hist.toString(), user, 64, regex);
            long dt = System.currentTimeMillis() - t0;
            String line = "";
            if (json != null) {
                line = new JSONObject(json).getJSONArray("content").getJSONObject(0).getString("text").trim();
            }
            Object[] parsed = json == null ? null : LocalCommandRouter.parseCompact(line, tools);
            Log.i(TAG, "ROUTE " + dt + " ms : " + text + " => [" + line + "] parsed="
                    + (parsed == null ? "null" : parsed[0] + " " + parsed[1])
                    + (json == null ? " ECHEC " + LiteRtNative.lastError() : ""));
            LiteRtNative.nativeUnload(h);
        } catch (Throwable t) {
            Log.e(TAG, "ERREUR route", t);
        }
    }

    /** Routeur sur un fichier : une ligne par phrase, « phrase<TAB>outils ». Un seul chargement du modèle. */
    static void runRoutesFile(Context ctx) {
        try {
            LocalLlmServer server = LocalLlmServer.get(ctx);
            for (String line : java.nio.file.Files.readAllLines(java.nio.file.Paths.get(ROUTES_FILE),
                    java.nio.charset.StandardCharsets.UTF_8)) {
                String[] f = line.split("\\t", -1);
                if (f.length < 2 || f[0].trim().isEmpty()) continue;
                String text = f[0];
                java.util.List<String> tools = java.util.Arrays.asList(f[1].split(","));
                // Même chemin que LocalCommandRouter.decide : messages + contrainte regex, via le serveur.
                JSONObject body = new JSONObject()
                        .put("messages", LocalCommandRouter.compactMessages(tools, text))
                        .put("regex", LocalCommandRouter.regex(tools))
                        .put("temperature", 0.2)
                        .put("max_tokens", 64)
                        .put("chat_template_kwargs", new JSONObject().put("enable_thinking", false));
                long t0 = System.currentTimeMillis();
                String out;
                try {
                    out = server.chat(body, 45_000).getJSONArray("choices").getJSONObject(0)
                            .getJSONObject("message").optString("content", "").trim();
                } catch (Exception e) {
                    out = "ECHEC " + e.getMessage();
                }
                long dt = System.currentTimeMillis() - t0;
                String verdict;
                try {
                    Object[] parsed = out.startsWith("ECHEC") ? null : LocalCommandRouter.parseCompact(out, tools);
                    if (parsed == null) {
                        verdict = " => REJETÉ";
                    } else {
                        JSONObject args = (JSONObject) parsed[1];
                        LocalCommandRouter.dropCopiedValues((String) parsed[0], args, text);
                        verdict = " => " + parsed[0] + " " + args;
                    }
                } catch (Exception e) {
                    verdict = " => ERREUR " + e.getMessage();
                }
                Log.i(TAG, "ROUTES " + dt + " ms | " + text + " | " + out + verdict);
            }
            Log.i(TAG, "ROUTES FIN");
        } catch (Throwable t) {
            Log.e(TAG, "ERREUR routes", t);
        }
    }

    /**
     * Banc des faits inventés : chaque phrase de {@link #HALLU_FILE} passe n fois par le vrai
     * chemin de discussion (chatbot puis E2B). Une ligne JSON par réponse, tag PegaseHallu.
     */
    static void runHallu(Context ctx, int n) {
        try {
            com.pegasuscorp.orbe.chat.LocalServerChatBackend b =
                    new com.pegasuscorp.orbe.chat.LocalServerChatBackend(ctx);
            java.util.List<String> lines = java.nio.file.Files.readAllLines(
                    java.nio.file.Paths.get(HALLU_FILE), java.nio.charset.StandardCharsets.UTF_8);
            int i = 0;
            for (String phrase : lines) {
                if (phrase.trim().isEmpty()) continue;
                i++;
                for (int k = 0; k < n; k++) {
                    JSONObject o = new JSONObject().put("i", i).put("k", k).put("phrase", phrase);
                    long t0 = System.currentTimeMillis();
                    try {
                        String out = b.benchReply(phrase);
                        o.put("out", out).put("ms", System.currentTimeMillis() - t0);
                    } catch (Throwable t) {
                        o.put("err", String.valueOf(t));
                    }
                    com.pegasuscorp.orbe.chatbot.ChatbotEngine.Decision d = b.benchDecisionForTest();
                    if (d != null) {
                        o.put("zone", d.zone).put("source", d.source).put("score", d.score)
                                .put("ideas", new org.json.JSONArray(d.ideas))
                                .put("facts", new org.json.JSONArray(d.facts))
                                .put("reply", d.reply);
                    }
                    o.put("raw", b.benchRawForTest()).put("guard", b.benchGuardForTest());
                    Log.i("PegaseHallu", "HALLU " + o);
                }
            }
            Log.i("PegaseHallu", "HALLU FIN");
        } catch (Throwable t) {
            Log.e("PegaseHallu", "HALLU ERREUR", t);
        }
    }
}
