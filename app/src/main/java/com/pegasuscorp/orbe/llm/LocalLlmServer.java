package com.pegasuscorp.orbe.llm;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.pegasuscorp.orbe.tools.HttpJson;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Serveur LLM local : {@code llama-server} (llama.cpp) lancé en sous-processus depuis
 * {@code nativeLibraryDir/libllamaserver.so}, API OpenAI sur 127.0.0.1.
 *
 * <p>Pourquoi un serveur plutôt que le JNI {@link LlamaCppEngine} : gabarit de chat Jinja
 * (format d'outils natif Qwen3.5), analyse des tool_calls, et surtout cache du préfixe
 * de prompt par slot (le système + outils ne sont pas relus à chaque commande).
 *
 * <p>Démarré à la demande, arrêté après {@link #IDLE_STOP_MS} sans requête (≈1,5 Go de RAM).
 */
public final class LocalLlmServer {

    private static final String TAG = "LocalLlmServer";
    /** Gemma 4 E2B (2026-11-23) : choisi au banc à l'aveugle, 12/12 en commandes compactes. */
    public static final String MODEL_FILE = "gemma-4-E2B-it-Q4_0.gguf";
    public static final String MODEL_LABEL = "Gemma 4 E2B";
    /** Ancien modèle (Qwen3.5-2B), supprimé une fois Gemma en place (−1,3 Go). */
    static final String LEGACY_MODEL_FILE = "Qwen3.5-2B-Q4_K_M.gguf";
    private static final int PORT = 18092;
    private static final long IDLE_STOP_MS = 20L * 60 * 1000;
    private static final long START_TIMEOUT_MS = 60_000;
    /** Slots des groupes d'outils du routeur (0–9). */
    public static final int SLOT_COUNT = 10;
    /** Slot de la discussion locale, et slot de la décision « recherche web ou non ». */
    public static final int CHAT_SLOT = 10;
    public static final int WEB_DECISION_SLOT = 11;
    /** Tâches de fond (résumés, tri SMS, mémoire) : ne touchent pas au cache de la discussion. */
    public static final int BACKGROUND_SLOT = 12;
    private static final int TOTAL_SLOTS = 13;
    /**
     * 4096 jetons par slot, contexte NON partagé : avec --kv-unified, une requête sur un slot
     * effaçait le cache des autres (mesuré : la décision web vidait la discussion → 244 jetons
     * relus au lieu de 13). Coût ≈ +1 Go, il reste ≈2,5 Go libres sur le Nothing.
     */
    private static final int TOTAL_SLOTS_FOR_CTX = 13;
    private static final int TOTAL_CTX = 4096 * TOTAL_SLOTS_FOR_CTX;

    private static final String PREFS = "local_llm";
    private static final String KEY_ENABLED = "commands_enabled";
    private static final String KEY_LOCAL_ONLY = "local_only";

    private static LocalLlmServer instance;

    private final Context app;

    /** Moteur LiteRT-LM (Gemma .litertlm, GPU) au lieu de llama-server. Réglage « moteur_litert ». */
    public static final String LITERT_MODEL_FILE = "gemma-4-E2B-it.litertlm";
    private static final String KEY_LITERT = "moteur_litert";
    private long litertHandle = 0;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private Process process;
    private ScheduledFuture<?> idleStop;
    private volatile boolean ready;

    private LocalLlmServer(Context ctx) {
        this.app = ctx.getApplicationContext();
    }

    public static synchronized LocalLlmServer get(Context ctx) {
        if (instance == null) instance = new LocalLlmServer(ctx);
        return instance;
    }

    // ---- Réglage / disponibilité ----

    public static boolean isEnabled(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, true);
    }

    public static void setEnabled(Context ctx, boolean on) {
        SharedPreferences p = ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        p.edit().putBoolean(KEY_ENABLED, on).apply();
        if (!on) get(ctx).stop();
    }

    /**
     * « Toujours local » : le cloud est débranché, toute la discussion passe par le modèle du
     * téléphone (choix de Yannick le 2026-11-23, pour vivre avec le local).
     */
    public static boolean isLitert(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_LITERT, false);
    }

    public static void setLitert(Context ctx, boolean on) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_LITERT, on).apply();
    }

    public static boolean isLocalOnly(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_LOCAL_ONLY, false);
    }

    public static void setLocalOnly(Context ctx, boolean on) {
        ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_LOCAL_ONLY, on).apply();
    }

    public static final String MODEL_URL =
            "https://huggingface.co/unsloth/gemma-4-E2B-it-GGUF/resolve/main/" + MODEL_FILE;
    /** Taille attendue (contrôle d'un téléchargement incomplet). */
    private static final long MODEL_MIN_BYTES = 2_900_000_000L;

    /** Modèle dans files/models (copie manuelle) ou dans le dossier externe de l'appli (bouton). */
    public File modelFile() {
        File internal = new File(ModelStore.modelsDir(app), MODEL_FILE);
        if (internal.isFile()) return internal;
        return downloadTarget();
    }

    /** Destination du bouton « Télécharger » (Android/data/…/files/models). */
    public File downloadTarget() {
        File ext = app.getExternalFilesDir("models");
        return new File(ext != null ? ext : ModelStore.modelsDir(app), MODEL_FILE);
    }

    public boolean isModelComplete() {
        File f = modelFile();
        boolean ok = f.isFile() && f.length() >= MODEL_MIN_BYTES;
        if (ok) deleteLegacyModel();
        return ok;
    }

    /** Supprime l'ancien Qwen (interne ou externe) une fois Gemma complet. */
    private void deleteLegacyModel() {
        File ext = app.getExternalFilesDir("models");
        for (File dir : new File[]{ModelStore.modelsDir(app), ext}) {
            if (dir == null) continue;
            File old = new File(dir, LEGACY_MODEL_FILE);
            if (old.isFile() && old.delete()) Log.i(TAG, "ancien modèle supprimé : " + old);
        }
    }

    /**
     * Lance le téléchargement du modèle (~3 Go) via le DownloadManager d'Android :
     * notification de progression système, reprise si l'appli est fermée.
     * @return id du téléchargement, ou -1.
     */
    public long startModelDownload() {
        try {
            File target = downloadTarget();
            if (target.isFile() && target.length() < MODEL_MIN_BYTES) {
                //noinspection ResultOfMethodCallIgnored
                target.delete();                     // reste d'un essai interrompu
            }
            android.app.DownloadManager dm = (android.app.DownloadManager)
                    app.getSystemService(Context.DOWNLOAD_SERVICE);
            android.app.DownloadManager.Request req = new android.app.DownloadManager.Request(
                    android.net.Uri.parse(MODEL_URL))
                    .setTitle("Modèle local Pégase (" + MODEL_LABEL + ")")
                    .setDescription("≈3 Go — Pégase sur le téléphone (commandes et discussion)")
                    .setNotificationVisibility(
                            android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalFilesDir(app, "models", MODEL_FILE)
                    .setAllowedOverMetered(false);           // Wi-Fi seulement
            return dm.enqueue(req);
        } catch (Exception e) {
            Log.e(TAG, "download", e);
            return -1;
        }
    }

    private File slotsDir() {
        File d = new File(app.getFilesDir(), "llm-slots");
        if (!d.isDirectory()) //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        return d;
    }

    private File binary() {
        return new File(app.getApplicationInfo().nativeLibraryDir, "libllamaserver.so");
    }

    /** Activé + binaire + modèle présents. */
    public boolean isAvailable() {
        if (usesLitert()) {
            return isEnabled(app) && new File(ModelStore.modelsDir(app), LITERT_MODEL_FILE).length() > 2_000_000_000L;
        }
        return isEnabled(app) && binary().canExecute() && isModelComplete();
    }

    /** Vrai si le moteur LiteRT-LM remplace llama-server. */
    public boolean usesLitert() {
        return isLitert(app);
    }

    /** Charge le modèle LiteRT une fois (GPU). Bloquant. */
    private synchronized boolean litertReady() {
        if (litertHandle != 0) return true;
        if (!isAvailable()) return false;
        java.io.File cache = new java.io.File(app.getCacheDir(), "litert");
        cache.mkdirs();
        litertHandle = LiteRtNative.nativeLoad(
                new File(ModelStore.modelsDir(app), LITERT_MODEL_FILE).getAbsolutePath(),
                cache.getAbsolutePath(), "gpu");
        if (litertHandle == 0) Log.e(TAG, "LiteRT : " + LiteRtNative.lastError());
        return litertHandle != 0;
    }

    /** Verrou : le moteur LiteRT ne traite qu'une génération à la fois. */
    private static final Object LITERT_LOCK = new Object();

    /**
     * Génération LiteRT en flux : {@code onText} reçoit le texte accumulé à chaque morceau.
     * Bloquant. @return le texte complet.
     */
    private String litertStream(JSONObject body, int readTimeoutMs,
            java.util.function.Consumer<String> onText) throws Exception {
        if (!litertReady()) throw new java.io.IOException("LiteRT indisponible : " + LiteRtNative.lastError());
        org.json.JSONArray msgs = body.getJSONArray("messages");
        String sys = "";
        org.json.JSONArray hist = new org.json.JSONArray();
        String user = null;
        for (int i = 0; i < msgs.length(); i++) {
            JSONObject m = msgs.getJSONObject(i);
            if (m.optString("role", "").equals("system") && sys.isEmpty()) {
                sys = m.optString("content", "");
            } else if (i == msgs.length() - 1) {
                user = m.toString();
            } else {
                hist.put(m);
            }
        }
        if (user == null) throw new java.io.IOException("LiteRT : pas de message utilisateur");
        String regex = body.optString("regex", null);
        if (regex != null && regex.isEmpty()) regex = null;
        int max = body.optInt("max_tokens", 256);
        synchronized (LITERT_LOCK) {
            long id = LiteRtNative.nativeStreamStart(litertHandle,
                    JSONObject.quote(sys), hist.toString(), user, max, regex);
            if (id == 0) throw new java.io.IOException("LiteRT : " + LiteRtNative.lastError());
            long deadline = System.currentTimeMillis() + readTimeoutMs;
            String last = "";
            try {
                while (true) {
                    String cur = LiteRtNative.nativeStreamText(id);
                    if (!cur.equals(last)) {
                        last = cur;
                        touch();
                        if (onText != null) onText.accept(cur);
                    }
                    String err = LiteRtNative.nativeStreamError(id);
                    if (err != null) throw new java.io.IOException("LiteRT : " + err);
                    if (LiteRtNative.nativeStreamDone(id)) break;
                    if (System.currentTimeMillis() > deadline) {
                        throw new java.io.IOException("LiteRT : délai dépassé");
                    }
                    Thread.sleep(40);
                }
                String fin = LiteRtNative.nativeStreamText(id);
                if (!fin.equals(last) && onText != null) onText.accept(fin);
                return fin;
            } finally {
                LiteRtNative.nativeStreamFinish(id);
            }
        }
    }

    /**
     * Réponse LiteRT au format OpenAI (choices[0].message.content). Le système et l'historique
     * passent tels quels ; la contrainte {@code regex} remplace la grammaire GBNF de llama.cpp.
     */
    private JSONObject litertChat(JSONObject body) throws Exception {
        if (!litertReady()) throw new java.io.IOException("LiteRT indisponible : " + LiteRtNative.lastError());
        org.json.JSONArray msgs = body.getJSONArray("messages");
        String sys = "";
        org.json.JSONArray hist = new org.json.JSONArray();
        String user = null;
        for (int i = 0; i < msgs.length(); i++) {
            JSONObject m = msgs.getJSONObject(i);
            String role = m.optString("role", "");
            if (role.equals("system") && sys.isEmpty()) {
                sys = m.optString("content", "");
            } else if (i == msgs.length() - 1) {
                user = m.toString();
            } else {
                hist.put(m);
            }
        }
        if (user == null) throw new java.io.IOException("LiteRT : pas de message utilisateur");
        String regex = body.optString("regex", null);
        if (regex != null && regex.isEmpty()) regex = null;
        int max = body.optInt("max_tokens", 256);
        String json = LiteRtNative.nativeGenerate(litertHandle,
                JSONObject.quote(sys), hist.toString(), user, max, regex);
        if (json == null) throw new java.io.IOException("LiteRT : " + LiteRtNative.lastError());
        String text = new JSONObject(json).getJSONArray("content").getJSONObject(0).getString("text");
        return new JSONObject().put("choices", new org.json.JSONArray().put(
                new JSONObject().put("message", new JSONObject().put("role", "assistant").put("content", text))));
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + PORT;
    }

    // ---- Cycle de vie ----

    /**
     * Démarre si besoin et attend que le serveur réponde. Bloquant : hors thread UI.
     * @return true si prêt.
     */
    public synchronized boolean ensureStarted() {
        touch();
        if (usesLitert()) return litertReady();
        if (ready && process != null && process.isAlive()) return true;
        if (!isAvailable()) return false;
        if (healthy()) { ready = true; return true; }   // instance déjà lancée (redémarrage appli)
        stopProcess();
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add(binary().getAbsolutePath());
            cmd.add("-m"); cmd.add(modelFile().getAbsolutePath());
            // Un slot (= un cache de préfixe) par groupe d'outils + discussion + décision web.
            cmd.add("-c"); cmd.add(String.valueOf(TOTAL_CTX));
            cmd.add("-np"); cmd.add(String.valueOf(TOTAL_SLOTS));
            cmd.add("-fa"); cmd.add("on");
            cmd.add("-ctk"); cmd.add("q8_0");
            cmd.add("-ctv"); cmd.add("q8_0");
            cmd.add("--slot-save-path"); cmd.add(slotsDir().getAbsolutePath() + "/");
            // 4 cœurs rapides (A78, cpu4–7) : le mot d'éveil et l'interface restent sur les petits.
            cmd.add("-t"); cmd.add("4");
            cmd.add("-C"); cmd.add("f0");
            cmd.add("--cpu-strict"); cmd.add("1");
            // Cache complet aussi pour les couches à fenêtre glissante (Gemma) : la discussion
            // ne relit que le nouveau message (mesuré : 26 jetons relus au lieu de 61–73).
            cmd.add("--swa-full");
            cmd.add("--jinja");
            cmd.add("--host"); cmd.add("127.0.0.1");
            cmd.add("--port"); cmd.add(String.valueOf(PORT));
            cmd.add("--no-webui");
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
            pb.directory(app.getCacheDir());
            process = pb.start();
            drainLog(process);
        } catch (Exception e) {
            Log.e(TAG, "start failed", e);
            process = null;
            return false;
        }
        long deadline = System.currentTimeMillis() + START_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (process == null || !process.isAlive()) {
                Log.e(TAG, "llama-server died during start");
                return false;
            }
            if (healthy()) {
                ready = true;
                Log.i(TAG, "ready");
                return true;
            }
            try { Thread.sleep(300); } catch (InterruptedException ie) { return false; }
        }
        Log.e(TAG, "start timeout");
        stopProcess();
        return false;
    }

    /**
     * Requête épinglée sur le slot du groupe {@code slot}.
     *
     * <p>On garde sur disque l'état arrêté PILE à la fin du préfixe fixe (système + outils +
     * exemples + en-tête du tour utilisateur), et on le recharge avant CHAQUE requête (~50 ms) :
     * seule la phrase est lue. Indispensable pour les modèles hybrides (Qwen3.5, état récurrent
     * qui ne peut pas « reculer ») ; sans risque pour les autres (Gemma). La coupe est trouvée
     * sans dépendre du format de conversation du modèle : juste avant le texte du dernier tour.
     *
     * @param templateBody messages + tools (comme la requête) pour rendre le préfixe exact.
     */
    public JSONObject chatInSlot(JSONObject body, int slot, String cacheName,
            JSONObject templateBody, int readTimeoutMs) throws Exception {
        if (usesLitert()) return chat(body, readTimeoutMs);     // pas de cache de préfixe en LiteRT
        if (slot < 0 || slot >= SLOT_COUNT || cacheName == null) return chat(body, readTimeoutMs);
        if (ensurePrefix(slot, cacheName, templateBody)) restore(slot, cacheName);
        body.put("id_slot", slot);
        body.put("cache_prompt", true);
        return chat(body, readTimeoutMs);
    }

    /** Crée le fichier de préfixe s'il manque (≈10–15 s la toute première fois). */
    public synchronized boolean ensurePrefix(int slot, String cacheName, JSONObject templateBody) {
        String file = slotFile(cacheName);
        if (new File(slotsDir(), file).isFile()) return true;
        try {
            touch();
            JSONObject rendered = HttpJson.postJson(baseUrl() + "/apply-template", null,
                    templateBody, 3_000, 20_000);
            String prompt = rendered.optString("prompt", "");
            int cut = prefixCut(prompt, templateBody);
            if (cut <= 0) return false;
            String prefix = prompt.substring(0, cut);
            HttpJson.postJson(baseUrl() + "/completion", null, new JSONObject()
                    .put("prompt", prefix).put("n_predict", 0)
                    .put("id_slot", slot).put("cache_prompt", true), 3_000, 120_000);
            HttpJson.postJson(baseUrl() + "/slots/" + slot + "?action=save", null,
                    new JSONObject().put("filename", file), 3_000, 30_000);
            Log.i(TAG, "préfixe sauvegardé : " + file);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "préfixe " + file + " : " + e.getMessage());
            return false;
        }
    }

    private void restore(int slot, String cacheName) {
        try {
            HttpJson.postJson(baseUrl() + "/slots/" + slot + "?action=restore", null,
                    new JSONObject().put("filename", slotFile(cacheName)), 3_000, 20_000);
        } catch (Exception e) {
            Log.w(TAG, "restore " + cacheName + " : " + e.getMessage());
        }
    }

    /** Supprime les fichiers de cache qui ne sont plus dans {@code keep} (prompt ou outils modifiés). */
    public void pruneCaches(java.util.Set<String> keep) {
        File[] files = slotsDir().listFiles();
        if (files == null) return;
        for (File f : files) {
            if (!keep.contains(f.getName())) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    /**
     * Position du début du texte du dernier message dans le prompt rendu (le préfixe s'arrête
     * juste avant, en-tête du tour compris). -1 si introuvable.
     */
    static int prefixCut(String prompt, JSONObject templateBody) {
        try {
            org.json.JSONArray msgs = templateBody.getJSONArray("messages");
            String last = msgs.getJSONObject(msgs.length() - 1).optString("content", "").trim();
            if (last.isEmpty()) return -1;
            String head = last.substring(0, Math.min(24, last.length()));
            return prompt.lastIndexOf(head);
        } catch (Exception e) {
            return -1;
        }
    }

    static String fileFor(String cacheName) {
        return slotFile(cacheName);
    }

    /** Le nom du modèle fait partie du fichier : un cache d'un autre modèle est inutilisable. */
    private static String slotFile(String cacheName) {
        String model = MODEL_FILE.replaceAll("\\.gguf$", "").replaceAll("[^a-zA-Z0-9]", "");
        return (model + "-" + cacheName).replaceAll("[^a-zA-Z0-9_.-]", "_") + ".bin";
    }

    /** POST /v1/chat/completions. Bloquant. */
    public JSONObject chat(JSONObject body, int readTimeoutMs) throws Exception {
        touch();
        if (usesLitert()) {
            try { return litertChat(body); } finally { touch(); }
        }
        try {
            return HttpJson.postJson(baseUrl() + "/v1/chat/completions", null, body,
                    3_000, readTimeoutMs);
        } finally {
            touch();
        }
    }

    /**
     * POST /v1/chat/completions en flux (SSE) : {@code onText} reçoit le texte accumulé à chaque
     * morceau. Bloquant. @return le texte complet.
     */
    public String chatStream(JSONObject body, int readTimeoutMs,
            java.util.function.Consumer<String> onText) throws Exception {
        touch();
        if (usesLitert()) {
            try { return litertStream(body, readTimeoutMs, onText); } finally { touch(); }
        }
        java.net.HttpURLConnection c = null;
        try {
            body.put("stream", true);
            lastTimings = null;
            c = (java.net.HttpURLConnection) new java.net.URL(baseUrl() + "/v1/chat/completions")
                    .openConnection();
            c.setConnectTimeout(3_000);
            c.setReadTimeout(readTimeoutMs);
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json");
            try (java.io.OutputStream os = c.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            if (code / 100 != 2) throw new java.io.IOException("llama-server HTTP " + code);
            StringBuilder acc = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    c.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (!line.startsWith("data:")) continue;
                    String data = line.substring(5).trim();
                    if (data.equals("[DONE]")) break;
                    JSONObject ev = new JSONObject(data);
                    if (ev.has("timings")) lastTimings = ev.optJSONObject("timings");
                    org.json.JSONArray ch = ev.optJSONArray("choices");
                    if (ch == null || ch.length() == 0) continue;
                    JSONObject delta = ch.getJSONObject(0).optJSONObject("delta");
                    String piece = delta != null ? delta.optString("content", "") : "";
                    if (piece.isEmpty() || "null".equals(piece)) continue;
                    acc.append(piece);
                    touch();
                    if (onText != null) onText.accept(acc.toString());
                }
            }
            return acc.toString();
        } finally {
            if (c != null) c.disconnect();
            touch();
        }
    }

    /** Compteurs de la dernière réponse en flux (llama-server « timings »), ou null. */
    public volatile JSONObject lastTimings;

    /** « relus 32 · cache 321 · lecture 37/s · écrits 28 à 9,2/s » pour le journal. */
    public static String describeTimings(JSONObject t) {
        if (t == null) return "?";
        return String.format(java.util.Locale.FRANCE, "relus %d · cache %d · lecture %.0f/s · écrits %d à %.1f/s",
                t.optInt("prompt_n"), t.optInt("cache_n"), t.optDouble("prompt_per_second", 0),
                t.optInt("predicted_n"), t.optDouble("predicted_per_second", 0));
    }

    public synchronized void stop() {
        stopProcess();
        if (litertHandle != 0) {
            LiteRtNative.nativeUnload(litertHandle);
            litertHandle = 0;
        }
    }

    // ---- Interne ----

    private boolean healthy() {
        try {
            JSONObject h = HttpJson.get(baseUrl() + "/health", null, 800, 800);
            return "ok".equals(h.optString("status"));
        } catch (Exception e) {
            return false;
        }
    }

    private synchronized void touch() {
        if (idleStop != null) idleStop.cancel(false);
        idleStop = timer.schedule(this::stop, IDLE_STOP_MS, TimeUnit.MILLISECONDS);
    }

    private void stopProcess() {
        ready = false;
        if (process != null) {
            try { process.destroy(); } catch (Exception ignored) {}
            process = null;
            Log.i(TAG, "stopped");
        }
    }

    private static void drainLog(Process p) {
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.contains("error") || line.contains("listening")
                            || line.contains("print_timing")) {
                        Log.d(TAG, line);
                    }
                }
            } catch (Exception ignored) {
            }
        }, "llama-server-log");
        t.setDaemon(true);
        t.start();
    }
}
