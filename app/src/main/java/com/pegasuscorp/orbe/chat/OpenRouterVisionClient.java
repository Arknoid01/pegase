package com.pegasuscorp.orbe.chat;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Analyse image / PDF via OpenRouter (modèle vision), hors chaîne texte Groq/Cerebras.
 */
public final class OpenRouterVisionClient {

    /** Vision par défaut — Qwen VL via OpenRouter (pas Gemini). */
    public static final String DEFAULT_VISION_MODEL = "qwen/qwen2.5-vl-72b-instruct";

    private static final int MAX_IMAGE_EDGE = 1280;
    private static final int JPEG_QUALITY = 82;
    private static final int MAX_PDF_BYTES = 12 * 1024 * 1024;
    private static final int CONNECT_MS = 20_000;
    private static final int READ_MS = 90_000;

    public interface Callback {
        void onSuccess(String analysis);
        void onError(String message);
    }

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private OpenRouterVisionClient() {}

    public static String visionModel(Context context) {
        if (context == null) return DEFAULT_VISION_MODEL;
        String id = context.getSharedPreferences("orbe_cloud_llm", Context.MODE_PRIVATE)
                .getString("openrouter_vision_model", DEFAULT_VISION_MODEL);
        return TextUtils.isEmpty(id) ? DEFAULT_VISION_MODEL : id;
    }

    public static void setVisionModel(Context context, String modelId) {
        if (context == null || TextUtils.isEmpty(modelId)) return;
        context.getSharedPreferences("orbe_cloud_llm", Context.MODE_PRIVATE)
                .edit()
                .putString("openrouter_vision_model", modelId.trim())
                .apply();
    }

    /** Analyse une image (JPEG/PNG/WebP…) depuis un Uri content:// ou file://. */
    public static void analyzeImageUri(Context context, Uri uri, String userPrompt,
            Callback callback) {
        Context app = context.getApplicationContext();
        IO.execute(() -> {
            try {
                byte[] jpeg = loadAndCompressImage(app, uri);
                if (jpeg == null || jpeg.length == 0) {
                    postError(callback, "Impossible de lire l'image.");
                    return;
                }
                String dataUrl = "data:image/jpeg;base64,"
                        + Base64.encodeToString(jpeg, Base64.NO_WRAP);
                String text = analyzeBlocking(app, userPrompt, imagePart(dataUrl), null);
                postSuccess(callback, text);
            } catch (Exception e) {
                postError(callback, ChatSpokenErrors.toUserMessage("OpenRouter Vision",
                        e.getMessage()));
            }
        });
    }

    /** Analyse un PDF (envoyé tel quel à OpenRouter file-parser). */
    public static void analyzePdfUri(Context context, Uri uri, String filename,
            String userPrompt, Callback callback) {
        Context app = context.getApplicationContext();
        IO.execute(() -> {
            try {
                byte[] pdf = readUriBytes(app, uri, MAX_PDF_BYTES);
                if (pdf == null || pdf.length == 0) {
                    postError(callback, "Impossible de lire le PDF.");
                    return;
                }
                if (pdf.length >= MAX_PDF_BYTES) {
                    postError(callback, "PDF trop volumineux (max 12 Mo).");
                    return;
                }
                String name = TextUtils.isEmpty(filename) ? "document.pdf" : filename;
                if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")) {
                    name = name + ".pdf";
                }
                String dataUrl = "data:application/pdf;base64,"
                        + Base64.encodeToString(pdf, Base64.NO_WRAP);
                String text = analyzeBlocking(app, userPrompt, null, filePart(name, dataUrl));
                postSuccess(callback, text);
            } catch (Exception e) {
                postError(callback, ChatSpokenErrors.toUserMessage("OpenRouter Vision",
                        e.getMessage()));
            }
        });
    }

    static String analyzeBlocking(Context app, String userPrompt,
            JSONObject imagePart, JSONObject filePart) throws Exception {
        String key = ApiKeyStore.getOpenRouterKey(app);
        if (TextUtils.isEmpty(key)) {
            throw new IllegalStateException(
                    "Clé OpenRouter manquante. Va dans Réglages → Clés API.");
        }
        String prompt = TextUtils.isEmpty(userPrompt)
                ? defaultPrompt(filePart != null)
                : userPrompt.trim();

        JSONArray content = new JSONArray();
        content.put(new JSONObject().put("type", "text").put("text", prompt));
        if (imagePart != null) content.put(imagePart);
        if (filePart != null) content.put(filePart);

        JSONObject userMsg = new JSONObject();
        userMsg.put("role", "user");
        userMsg.put("content", content);

        JSONArray messages = new JSONArray();
        messages.put(new JSONObject()
                .put("role", "system")
                .put("content", "Tu es Pégase. Réponds en français, clair et oral "
                        + "(pas de markdown, pas de listes à puces). "
                        + "Décris, extrais le texte utile, et synthétise."));
        messages.put(userMsg);

        JSONObject root = new JSONObject();
        root.put("model", visionModel(app));
        root.put("messages", messages);
        root.put("temperature", 0.4);
        root.put("max_tokens", 1200);
        // PDF : parser OpenRouter gratuit Cloudflare (évite coût OCR Mistral par défaut)
        if (filePart != null) {
            JSONArray plugins = new JSONArray();
            JSONObject plugin = new JSONObject();
            plugin.put("id", "file-parser");
            plugin.put("pdf", new JSONObject().put("engine", "cloudflare-ai"));
            plugins.put(plugin);
            root.put("plugins", plugins);
        }

        String raw = post(key, root.toString());
        LlmReply reply = GroqCompletionParser.parse(raw);
        String text = reply != null && reply.content != null ? reply.content.trim() : "";
        if (text.isEmpty()) {
            throw new IllegalStateException("Réponse vision vide.");
        }
        return sanitizeSpeech(text);
    }

    private static String sanitizeSpeech(String text) {
        try {
            return com.pegasuscorp.orbe.llm.PegasePrompt.sanitizeForSpeech(text);
        } catch (Exception e) {
            return text;
        }
    }

    private static String defaultPrompt(boolean pdf) {
        if (pdf) {
            return "Analyse ce PDF en français : résumé clair, points importants, "
                    + "et extrait les infos utiles. Sois concis (voix haute).";
        }
        return "Analyse cette image en français : décris ce que tu vois, "
                + "lis le texte visible, et dis ce qui est important. "
                + "Sois concis (voix haute).";
    }

    private static JSONObject imagePart(String dataUrl) throws Exception {
        return new JSONObject()
                .put("type", "image_url")
                .put("image_url", new JSONObject().put("url", dataUrl));
    }

    private static JSONObject filePart(String filename, String dataUrl) throws Exception {
        return new JSONObject()
                .put("type", "file")
                .put("file", new JSONObject()
                        .put("filename", filename)
                        .put("file_data", dataUrl));
    }

    private static String post(String apiKey, String body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection)
                new URL(ProviderChain.OPENROUTER_URL).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(CONNECT_MS);
        conn.setReadTimeout(READ_MS);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setRequestProperty("HTTP-Referer", "https://pegase.local");
        conn.setRequestProperty("X-Title", "Pegase");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(bytes);
        }
        int code = conn.getResponseCode();
        InputStream stream = code >= 200 && code < 300
                ? conn.getInputStream() : conn.getErrorStream();
        String response = readAll(stream);
        conn.disconnect();
        if (code < 200 || code >= 300) {
            String detail = response;
            try {
                JSONObject err = new JSONObject(response).optJSONObject("error");
                if (err != null) detail = err.optString("message", response);
            } catch (Exception ignored) {}
            throw new IllegalStateException("HTTP " + code + " — " + detail);
        }
        return response;
    }

    private static byte[] loadAndCompressImage(Context ctx, Uri uri) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            BitmapFactory.decodeStream(in, null, bounds);
        }
        int sample = 1;
        int maxSide = Math.max(bounds.outWidth, bounds.outHeight);
        while (maxSide / sample > MAX_IMAGE_EDGE * 2) sample *= 2;

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = Math.max(1, sample);
        Bitmap bmp;
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            bmp = BitmapFactory.decodeStream(in, null, opts);
        }
        if (bmp == null) return null;

        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int edge = Math.max(w, h);
        if (edge > MAX_IMAGE_EDGE) {
            float scale = MAX_IMAGE_EDGE / (float) edge;
            Bitmap scaled = Bitmap.createScaledBitmap(bmp,
                    Math.max(1, Math.round(w * scale)),
                    Math.max(1, Math.round(h * scale)), true);
            if (scaled != bmp) bmp.recycle();
            bmp = scaled;
        }

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bos);
        bmp.recycle();
        return bos.toByteArray();
    }

    private static byte[] readUriBytes(Context ctx, Uri uri, int maxBytes) throws Exception {
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
                int total = 0;
            while ((n = in.read(buf)) >= 0) {
                total += n;
                if (total > maxBytes) {
                    return null;
                }
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        }
    }

    private static String readAll(InputStream stream) throws Exception {
        if (stream == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = stream.read(buf)) >= 0) bos.write(buf, 0, n);
        return bos.toString(StandardCharsets.UTF_8.name());
    }

    private static void postSuccess(Callback cb, String text) {
        if (cb == null) return;
        new android.os.Handler(android.os.Looper.getMainLooper())
                .post(() -> cb.onSuccess(text));
    }

    private static void postError(Callback cb, String msg) {
        if (cb == null) return;
        new android.os.Handler(android.os.Looper.getMainLooper())
                .post(() -> cb.onError(msg != null ? msg : "Erreur vision"));
    }
}
