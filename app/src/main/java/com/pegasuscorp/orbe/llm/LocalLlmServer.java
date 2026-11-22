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
    public static final String MODEL_FILE = "Qwen3.5-2B-Q4_K_M.gguf";
    public static final String MODEL_LABEL = "Qwen3.5-2B";
    private static final int PORT = 18092;
    private static final long IDLE_STOP_MS = 10L * 60 * 1000;
    private static final long START_TIMEOUT_MS = 60_000;
    public static final int SLOT_COUNT = 10;
    private static final int SLOT_CTX = 1536;

    private static final String PREFS = "local_llm";
    private static final String KEY_ENABLED = "commands_enabled";

    private static LocalLlmServer instance;

    private final Context app;
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

    public static final String MODEL_URL =
            "https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/resolve/main/" + MODEL_FILE;
    /** Taille attendue (contrôle d'un téléchargement incomplet). */
    private static final long MODEL_MIN_BYTES = 1_200_000_000L;

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
        return f.isFile() && f.length() >= MODEL_MIN_BYTES;
    }

    /**
     * Lance le téléchargement du modèle (~1,3 Go) via le DownloadManager d'Android :
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
                    .setDescription("≈1,3 Go — commandes traitées sur le téléphone")
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
        return isEnabled(app) && binary().canExecute() && isModelComplete();
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
        if (ready && process != null && process.isAlive()) return true;
        if (!isAvailable()) return false;
        if (healthy()) { ready = true; return true; }   // instance déjà lancée (redémarrage appli)
        stopProcess();
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add(binary().getAbsolutePath());
            cmd.add("-m"); cmd.add(modelFile().getAbsolutePath());
            // Un slot (= un cache de préfixe) par groupe d'outils, 1536 tokens chacun ;
            // les caches sont sauvegardés sur disque (slotsDir) et rechargés au démarrage.
            cmd.add("-c"); cmd.add(String.valueOf(SLOT_CTX * SLOT_COUNT));
            cmd.add("-np"); cmd.add(String.valueOf(SLOT_COUNT));
            cmd.add("--slot-save-path"); cmd.add(slotsDir().getAbsolutePath() + "/");
            cmd.add("-t"); cmd.add("4");
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
     * <p>Qwen3.5 est hybride (couches récurrentes) : un état ne peut pas « reculer ». On garde
     * donc sur disque l'état arrêté PILE à la fin du préfixe fixe (système + outils + exemples,
     * jusqu'au jeton {@code <|im_start|>} du tour utilisateur), et on le recharge avant CHAQUE
     * requête (~50 ms) : seuls la phrase et l'en-tête sont lus (~20–40 jetons).
     *
     * @param templateBody messages + tools (comme la requête) pour rendre le préfixe exact.
     */
    public JSONObject chatInSlot(JSONObject body, int slot, String cacheName,
            JSONObject templateBody, int readTimeoutMs) throws Exception {
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
            int cut = prompt.lastIndexOf("<|im_start|>user");
            if (cut < 0) return false;
            String prefix = prompt.substring(0, cut + "<|im_start|>".length());
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

    static String fileFor(String cacheName) {
        return slotFile(cacheName);
    }

    private static String slotFile(String cacheName) {
        return cacheName.replaceAll("[^a-zA-Z0-9_.-]", "_") + ".bin";
    }

    /** POST /v1/chat/completions. Bloquant. */
    public JSONObject chat(JSONObject body, int readTimeoutMs) throws Exception {
        touch();
        try {
            return HttpJson.postJson(baseUrl() + "/v1/chat/completions", null, body,
                    3_000, readTimeoutMs);
        } finally {
            touch();
        }
    }

    public synchronized void stop() {
        stopProcess();
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
