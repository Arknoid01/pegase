package com.pegasuscorp.orbe.tools;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Requêtes HTTP JSON (GET/POST/PUT) avec gestion du code réponse. */
public final class HttpJson {

    private HttpJson() {}

    public static JSONObject get(String urlStr) throws Exception {
        return get(urlStr, null);
    }

    public static JSONObject get(String urlStr, Map<String, String> headers) throws Exception {
        HttpURLConnection conn = open(urlStr, "GET", headers);
        return readJson(conn);
    }

    public static JSONObject postForm(String urlStr, Map<String, String> form,
                                      Map<String, String> headers) throws Exception {
        HttpURLConnection conn = open(urlStr, "POST", headers);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        String body = encodeForm(form);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        return readJson(conn);
    }

    public static void putJson(String urlStr, Map<String, String> headers, JSONObject body)
            throws Exception {
        HttpURLConnection conn = open(urlStr, "PUT", headers);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        byte[] bytes = body != null ? body.toString().getBytes(StandardCharsets.UTF_8) : new byte[0];
        try (OutputStream os = conn.getOutputStream()) {
            os.write(bytes);
        }
        readOptionalJson(conn);
    }

    public static void postEmpty(String urlStr, Map<String, String> headers) throws Exception {
        HttpURLConnection conn = open(urlStr, "POST", headers);
        conn.setDoOutput(true);
        conn.getOutputStream().close();
        readOptionalJson(conn);
    }

    /** DELETE — accepte 200/204 (ex. terminate RunPod). */
    public static void delete(String urlStr, Map<String, String> headers) throws Exception {
        HttpURLConnection conn = open(urlStr, "DELETE", headers);
        int code = conn.getResponseCode();
        String text = readBody(conn, code);
        conn.disconnect();
        if (code >= 400) {
            throw new IllegalStateException("HTTP " + code + (text.isEmpty() ? "" : ": " + text));
        }
    }

    /** POST JSON → JSONObject réponse. */
    public static JSONObject postJson(String urlStr, Map<String, String> headers, JSONObject body)
            throws Exception {
        return postJson(urlStr, headers, body, 10_000, 60_000);
    }

    public static JSONObject postJson(String urlStr, Map<String, String> headers, JSONObject body,
            int connectTimeoutMs, int readTimeoutMs) throws Exception {
        HttpURLConnection conn = open(urlStr, "POST", headers, connectTimeoutMs, readTimeoutMs);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        byte[] bytes = body != null ? body.toString().getBytes(StandardCharsets.UTF_8) : new byte[0];
        try (OutputStream os = conn.getOutputStream()) {
            os.write(bytes);
        }
        return readJson(conn);
    }

    public static JSONObject get(String urlStr, Map<String, String> headers,
            int connectTimeoutMs, int readTimeoutMs) throws Exception {
        HttpURLConnection conn = open(urlStr, "GET", headers, connectTimeoutMs, readTimeoutMs);
        return readJson(conn);
    }

    /** GET → JSONArray (ex. liste network volumes RunPod). */
    public static JSONArray getArray(String urlStr, Map<String, String> headers,
            int connectTimeoutMs, int readTimeoutMs) throws Exception {
        HttpURLConnection conn = open(urlStr, "GET", headers, connectTimeoutMs, readTimeoutMs);
        int code = conn.getResponseCode();
        String text = readBody(conn, code);
        conn.disconnect();
        if (code >= 400) {
            throw new IllegalStateException("HTTP " + code + (text.isEmpty() ? "" : ": " + text));
        }
        if (text == null || text.trim().isEmpty()) return new JSONArray();
        String t = text.trim();
        if (t.startsWith("[")) return new JSONArray(t);
        // Parfois wrappé { "items": [...] }
        JSONObject o = new JSONObject(t);
        if (o.has("items")) return o.getJSONArray("items");
        if (o.has("networkVolumes")) return o.getJSONArray("networkVolumes");
        throw new IllegalStateException("Réponse JSON non-array : " + t.substring(0, Math.min(80, t.length())));
    }

    private static HttpURLConnection open(String urlStr, String method, Map<String, String> headers)
            throws Exception {
        return open(urlStr, method, headers, 10_000, 12_000);
    }

    private static HttpURLConnection open(String urlStr, String method, Map<String, String> headers,
            int connectTimeoutMs, int readTimeoutMs) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(connectTimeoutMs);
        conn.setReadTimeout(readTimeoutMs);
        conn.setRequestProperty("User-Agent", "Orbe-Pegase/1.0");
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    conn.setRequestProperty(entry.getKey(), entry.getValue());
                }
            }
        }
        return conn;
    }

    private static JSONObject readJson(HttpURLConnection conn) throws Exception {
        int code = conn.getResponseCode();
        String text = readBody(conn, code);
        conn.disconnect();
        if (code >= 400) {
            throw new IllegalStateException("HTTP " + code + (text.isEmpty() ? "" : ": " + text));
        }
        if (text.isEmpty()) return new JSONObject();
        return new JSONObject(text);
    }

    private static void readOptionalJson(HttpURLConnection conn) throws Exception {
        int code = conn.getResponseCode();
        String text = readBody(conn, code);
        conn.disconnect();
        if (code >= 400) {
            throw new IllegalStateException("HTTP " + code + (text.isEmpty() ? "" : ": " + text));
        }
    }

    private static String readBody(HttpURLConnection conn, int code) throws Exception {
        BufferedReader br = new BufferedReader(new InputStreamReader(
                code < 400 ? conn.getInputStream() : conn.getErrorStream(),
                StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        return sb.toString();
    }

    private static String encodeForm(Map<String, String> form) {
        if (form == null || form.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : form.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
            sb.append('=');
            sb.append(URLEncoder.encode(entry.getValue() != null ? entry.getValue() : "",
                    StandardCharsets.UTF_8));
        }
        return sb.toString();
    }
}
