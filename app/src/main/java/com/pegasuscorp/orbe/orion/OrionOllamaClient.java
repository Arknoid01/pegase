package com.pegasuscorp.orbe.orion;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Client Ollama {@code /api/generate} en streaming NDJSON.
 * Pas de nouvelle lib — {@link HttpURLConnection}.
 */
public final class OrionOllamaClient {

    public static final String MODEL = "qwen3-coder:30b";
    public static final int TIMEOUT_MS = 180_000;
    /** Téléchargement ~19 Go — timeout long. */
    public static final int PULL_TIMEOUT_MS = 45 * 60 * 1000;

    private static final java.util.concurrent.atomic.AtomicBoolean CANCELLED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** Injectable pour tests. */
    public interface GenerateTransport {
        void generate(String ollamaUrl, String bearerToken, String prompt,
                OrionStreamCallback cb) throws Exception;
    }

    public interface EnsureModelTransport {
        /** @return true si le modèle est utilisable après l'appel */
        boolean ensure(String ollamaUrl, String bearerToken,
                ProgressCallback progress) throws Exception;
    }

    public interface ProgressCallback {
        void onProgress(String message);
    }

    private static volatile GenerateTransport transport = OrionOllamaClient::generateHttp;
    private static volatile EnsureModelTransport ensureTransport =
            OrionOllamaClient::ensurePreferredModelHttp;

    private OrionOllamaClient() {}

    public static void setTransportForTests(GenerateTransport t) {
        transport = t != null ? t : OrionOllamaClient::generateHttp;
    }

    public static void setEnsureTransportForTests(EnsureModelTransport t) {
        ensureTransport = t != null ? t : OrionOllamaClient::ensurePreferredModelHttp;
    }

    public static void requestCancel() {
        CANCELLED.set(true);
    }

    public static boolean isCancelled() {
        return CANCELLED.get();
    }

    public static void generate(String ollamaUrl, String bearerToken, String prompt,
            OrionStreamCallback cb) {
        CANCELLED.set(false);
        try {
            transport.generate(ollamaUrl, bearerToken, prompt, cb);
        } catch (Exception e) {
            if (cb != null) {
                cb.onError(e.getMessage() == null ? "erreur Orion" : e.getMessage());
            }
        }
    }

    static void generateHttp(String ollamaUrl, String bearerToken, String prompt,
            OrionStreamCallback cb) throws Exception {
        if (cb == null) return;
        if (TextUtils.isEmpty(ollamaUrl)) {
            cb.onError("URL Ollama manquante.");
            return;
        }
        String base = normalizeBase(ollamaUrl);

        List<String> installed;
        try {
            installed = listModelNames(base, bearerToken);
        } catch (IllegalStateException auth) {
            cb.onError(auth.getMessage());
            return;
        }
        String model = pickModel(installed);
        if (model == null) {
            cb.onError("Ollama répond mais aucun modèle n'est installé sur le pod.\n"
                    + "Relance Orion : le téléchargement de " + MODEL
                    + " démarre automatiquement (~19 Go).");
            return;
        }

        JSONObject body = new JSONObject()
                .put("model", model)
                .put("prompt", prompt == null ? "" : prompt)
                .put("stream", true)
                .put("options", new JSONObject()
                        .put("temperature", 0.7)
                        .put("num_ctx", 32768));

        Exception lastFail = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            if (CANCELLED.get()) {
                cb.onError("Génération arrêtée.");
                return;
            }
            try {
                if (postGenerateOnce(base, bearerToken, body, cb)) {
                    return; // onComplete ou onError déjà appelé
                }
                // 502/503/504 → retry
                if (attempt < 3) {
                    cb.onToken(""); // no-op keep alive
                    Thread.sleep(2_000L * attempt);
                }
            } catch (GatewayRetryException e) {
                lastFail = e;
                if (attempt < 3) {
                    Thread.sleep(2_000L * attempt);
                    continue;
                }
                cb.onError(e.getMessage());
                return;
            } catch (Exception e) {
                cb.onError(e.getMessage() == null ? "erreur Orion" : e.getMessage());
                return;
            }
        }
        if (lastFail != null) {
            cb.onError(lastFail.getMessage());
        } else {
            cb.onError("Orion n'a pas répondu (proxy RunPod).");
        }
    }

    /**
     * @return true si terminé (succès ou erreur définitive déjà signalée au cb)
     * @throws GatewayRetryException si 502/503/504 à retenter
     */
    private static boolean postGenerateOnce(String base, String bearerToken, JSONObject body,
            OrionStreamCallback cb) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(base + "/api/generate")
                .openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(20_000);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("User-Agent", "Orbe-Pegase/1.0");
        if (!TextUtils.isEmpty(bearerToken)) {
            conn.setRequestProperty("Authorization", "Bearer " + bearerToken);
        }
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(bytes);
        }

        int code = conn.getResponseCode();
        if (code == 502 || code == 503 || code == 504) {
            String err = readAll(conn);
            conn.disconnect();
            throw new GatewayRetryException(formatHttpError(code, err,
                    body.optString("model", MODEL), null));
        }
        if (code >= 400) {
            String err = readAll(conn);
            conn.disconnect();
            cb.onError(formatHttpError(code, err, body.optString("model", MODEL), null));
            return true;
        }

        StringBuilder full = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (CANCELLED.get()) {
                    conn.disconnect();
                    cb.onError("Génération arrêtée.");
                    return true;
                }
                line = line.trim();
                if (line.isEmpty()) continue;
                JSONObject chunk = new JSONObject(line);
                String token = chunk.optString("response", "");
                boolean done = chunk.optBoolean("done", false);
                if (!token.isEmpty()) {
                    full.append(token);
                    cb.onToken(token);
                }
                if (done) break;
            }
        } finally {
            conn.disconnect();
        }
        if (CANCELLED.get()) {
            cb.onError("Génération arrêtée.");
            return true;
        }
        cb.onComplete(full.toString());
        return true;
    }

    private static final class GatewayRetryException extends Exception {
        GatewayRetryException(String message) {
            super(message);
        }
    }

    /**
     * Si aucun modèle utilisable : {@code POST /api/pull} de {@link #MODEL}.
     * @return true si un modèle est ensuite disponible
     */
    public static boolean ensurePreferredModel(String ollamaUrl, String bearerToken,
            ProgressCallback progress) throws Exception {
        return ensureTransport.ensure(ollamaUrl, bearerToken, progress);
    }

    static boolean ensurePreferredModelHttp(String ollamaUrl, String bearerToken,
            ProgressCallback progress) throws Exception {
        String base = normalizeBase(ollamaUrl);
        if (base.isEmpty()) return false;
        List<String> installed = listModelNames(base, bearerToken);
        if (pickModel(installed) != null) return true;
        if (progress != null) {
            progress.onProgress("Téléchargement " + MODEL + " (~19 Go)…");
        }
        boolean pulled = pullModelHttp(base, bearerToken, MODEL, progress);
        if (!pulled) return false;
        installed = listModelNames(base, bearerToken);
        return pickModel(installed) != null;
    }

    /** Liste les modèles via {@code GET /api/tags}. */
    public static List<String> listModels(String ollamaUrl, String bearerToken) {
        return listModelNames(normalizeBase(ollamaUrl), bearerToken);
    }

    static boolean pullModelHttp(String base, String bearerToken, String model,
            ProgressCallback progress) throws Exception {
        JSONObject body = new JSONObject()
                .put("name", model == null ? MODEL : model)
                .put("stream", true);
        HttpURLConnection conn = (HttpURLConnection) new URL(base + "/api/pull")
                .openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(20_000);
        conn.setReadTimeout(PULL_TIMEOUT_MS);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("User-Agent", "Orbe-Pegase/1.0");
        if (!TextUtils.isEmpty(bearerToken)) {
            conn.setRequestProperty("Authorization", "Bearer " + bearerToken);
        }
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(bytes);
        }
        int code = conn.getResponseCode();
        if (code >= 400) {
            String err = readAll(conn);
            conn.disconnect();
            throw new IllegalStateException("Pull HTTP " + code
                    + (err.isEmpty() ? "" : ": " + err));
        }
        boolean success = false;
        long lastUi = 0L;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (CANCELLED.get()) {
                    conn.disconnect();
                    return false;
                }
                line = line.trim();
                if (line.isEmpty()) continue;
                JSONObject chunk = new JSONObject(line);
                String status = chunk.optString("status", "");
                if ("success".equalsIgnoreCase(status)) {
                    success = true;
                }
                long now = System.currentTimeMillis();
                if (progress != null && now - lastUi > 1500L) {
                    lastUi = now;
                    progress.onProgress(formatPullStatus(chunk));
                }
            }
        } finally {
            conn.disconnect();
        }
        return success;
    }

    static String formatPullStatus(JSONObject chunk) {
        if (chunk == null) return "Téléchargement…";
        String status = chunk.optString("status", "téléchargement");
        long total = chunk.optLong("total", 0L);
        long completed = chunk.optLong("completed", 0L);
        if (total > 0L) {
            int pct = (int) Math.min(100L, (completed * 100L) / total);
            return status + " · " + pct + "%";
        }
        return status.isEmpty() ? "Téléchargement…" : status;
    }

    /** Parse une ligne NDJSON Ollama — pour tests unitaires. */
    public static ParsedChunk parseStreamLine(String line) throws Exception {
        if (line == null || line.trim().isEmpty()) {
            return new ParsedChunk("", false);
        }
        JSONObject chunk = new JSONObject(line.trim());
        return new ParsedChunk(chunk.optString("response", ""), chunk.optBoolean("done", false));
    }

    /**
     * Choisit le modèle à appeler : exact {@link #MODEL}, sinon variante qwen3-coder,
     * sinon premier modèle listé.
     */
    public static String pickModel(List<String> installed) {
        if (installed == null || installed.isEmpty()) return null;
        for (String name : installed) {
            if (MODEL.equalsIgnoreCase(name)) return name;
        }
        for (String name : installed) {
            if (name != null && name.toLowerCase(Locale.ROOT).startsWith("qwen3-coder")) {
                return name;
            }
        }
        return installed.get(0);
    }

    /** True si le modèle préféré (ou une variante qwen3-coder) est présent. */
    public static boolean hasUsableModel(List<String> installed) {
        return pickModel(installed) != null;
    }

    /** Extrait les noms depuis la réponse {@code /api/tags}. */
    public static List<String> modelNamesFromTags(JSONObject tags) {
        List<String> out = new ArrayList<>();
        if (tags == null) return out;
        JSONArray models = tags.optJSONArray("models");
        if (models == null) return out;
        for (int i = 0; i < models.length(); i++) {
            JSONObject m = models.optJSONObject(i);
            if (m == null) continue;
            String name = m.optString("name", "").trim();
            if (name.isEmpty()) name = m.optString("model", "").trim();
            if (!name.isEmpty()) out.add(name);
        }
        return out;
    }

    public static final class ParsedChunk {
        public final String token;
        public final boolean done;

        public ParsedChunk(String token, boolean done) {
            this.token = token != null ? token : "";
            this.done = done;
        }
    }

    static String normalizeBase(String ollamaUrl) {
        if (ollamaUrl == null) return "";
        String base = ollamaUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }

    private static List<String> listModelNames(String base, String bearerToken) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(base + "/api/tags")
                    .openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8_000);
            conn.setReadTimeout(15_000);
            conn.setRequestProperty("User-Agent", "Orbe-Pegase/1.0");
            if (!TextUtils.isEmpty(bearerToken)) {
                conn.setRequestProperty("Authorization", "Bearer " + bearerToken);
            }
            int code = conn.getResponseCode();
            if (code == 401 || code == 403) {
                conn.disconnect();
                // Ne pas confondre « auth » avec « aucun modèle »
                throw new IllegalStateException("Auth Ollama refusée (HTTP " + code
                        + "). Le token Pégase doit être IDENTIQUE à ORION_TOKEN dans setup.sh.");
            }
            if (code == 502 || code == 503 || code == 504) {
                conn.disconnect();
                throw new IllegalStateException(formatHttpError(code, "", MODEL, null));
            }
            if (code >= 400) {
                conn.disconnect();
                return new ArrayList<>();
            }
            String raw = readStream(conn.getInputStream());
            conn.disconnect();
            return modelNamesFromTags(new JSONObject(raw));
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private static String formatHttpError(int code, String err, String model,
            List<String> installed) {
        String body = sanitizeErrorBody(err);
        String lower = body.toLowerCase(Locale.ROOT);
        if (code == 502 || code == 503 || code == 504
                || lower.contains("waiting for service")
                || lower.contains("bad gateway")) {
            return "RunPod 502 — Ollama ne répond pas derrière le proxy (port 11435).\n"
                    + "Souvent : modèle en cours de chargement en VRAM, ou ollama serve planté.\n"
                    + "Attends 1–2 min et réessaie, ou Éteindre → relancer Orion.\n"
                    + "Sur le pod : tail -n 50 /workspace/ollama.log";
        }
        if (code == 404 && (lower.contains("model") || lower.contains("not found"))) {
            StringBuilder sb = new StringBuilder();
            sb.append("Modèle « ").append(model).append(" » introuvable sur Ollama (HTTP 404).\n");
            if (installed != null && !installed.isEmpty()) {
                StringBuilder j = new StringBuilder();
                for (int i = 0; i < installed.size(); i++) {
                    if (i > 0) j.append(", ");
                    j.append(installed.get(i));
                }
                sb.append("Installés : ").append(j);
            } else {
                sb.append("Aucun modèle listé — lance : ollama pull ").append(MODEL);
            }
            return sb.toString();
        }
        if (code == 404) {
            return "HTTP 404 sur /api/generate — proxy RunPod ou Ollama inaccessible.\n"
                    + "Vérifie que le pod est RUNNING et que le port 11435 (proxy) est exposé.";
        }
        if (body.isEmpty()) return "HTTP " + code;
        return "HTTP " + code + ": " + body;
    }

    /** Évite d'afficher la page HTML RunPod entière dans l'UI. */
    static String sanitizeErrorBody(String err) {
        if (err == null) return "";
        String t = err.trim();
        if (t.isEmpty()) return "";
        String lower = t.toLowerCase(Locale.ROOT);
        if (lower.contains("<html") || lower.contains("<!doctype") || lower.contains("<svg")) {
            if (lower.contains("waiting for service")) {
                return "Waiting for service to respond (RunPod)";
            }
            return "(réponse HTML RunPod)";
        }
        if (t.length() > 280) t = t.substring(0, 277) + "…";
        return t;
    }

    private static String readAll(HttpURLConnection conn) {
        try {
            return readStream(conn.getErrorStream() != null
                    ? conn.getErrorStream() : conn.getInputStream());
        } catch (Exception e) {
            return "";
        }
    }

    private static String readStream(java.io.InputStream in) throws Exception {
        if (in == null) return "";
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        return sb.toString();
    }
}
