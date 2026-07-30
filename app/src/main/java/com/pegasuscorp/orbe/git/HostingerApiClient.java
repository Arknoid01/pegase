package com.pegasuscorp.orbe.git;

import android.text.TextUtils;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Validation token Hostinger + déclenchement webhook de déploiement (optionnel).
 */
public final class HostingerApiClient {

    private static final String TAG = "HostingerApi";
    private static final String API_DOMAINS =
            "https://api.hostinger.com/api/domains/v1/portfolio";
    private static final int TIMEOUT_MS = 20_000;

    public static final class Validation {
        public final boolean ok;
        public final String message;

        public Validation(boolean ok, String message) {
            this.ok = ok;
            this.message = message == null ? "" : message;
        }
    }

    private HostingerApiClient() {}

    public static Validation validateToken(String token) {
        if (TextUtils.isEmpty(token)) {
            return new Validation(false, "Token Hostinger vide.");
        }
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(API_DOMAINS).openConnection();
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setRequestMethod("GET");
            c.setRequestProperty("Authorization", "Bearer " + token);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("User-Agent", "Orbe-Pegase");
            int code = c.getResponseCode();
            String body = readAll(code >= 400 ? c.getErrorStream() : c.getInputStream());
            c.disconnect();
            if (code == 200 || code == 201) {
                return new Validation(true, "Hostinger OK — token accepté.");
            }
            if (code == 401 || code == 403) {
                return new Validation(false, "Token Hostinger refusé (HTTP " + code + ").");
            }
            // Certains comptes n'ont pas le scope domains — 404/422 peut quand même
            // indiquer que le Bearer est reconnu (pas 401).
            if (code == 404 || code == 422) {
                return new Validation(true,
                        "Hostinger : token reconnu (endpoint portfolio HTTP " + code + ").");
            }
            return new Validation(false, "Hostinger HTTP " + code + " : " + clip(body, 120));
        } catch (Exception e) {
            Log.w(TAG, "validateToken", e);
            return new Validation(false, "Validation Hostinger impossible : "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    /** POST vide (ou JSON minimal) vers le webhook de deploy Hostinger. */
    public static Validation triggerDeployWebhook(String webhookUrl) {
        if (TextUtils.isEmpty(webhookUrl)) {
            return new Validation(false, "Pas de webhook Hostinger configuré.");
        }
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(webhookUrl.trim()).openConnection();
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setRequestProperty("User-Agent", "Orbe-Pegase");
            byte[] body = "{\"source\":\"orbe\"}".getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(body.length);
            try (OutputStream os = c.getOutputStream()) {
                os.write(body);
            }
            int code = c.getResponseCode();
            String resp = readAll(code >= 400 ? c.getErrorStream() : c.getInputStream());
            c.disconnect();
            if (code >= 200 && code < 300) {
                return new Validation(true, "Déploiement Hostinger déclenché (HTTP " + code + ").");
            }
            return new Validation(false, "Webhook Hostinger HTTP " + code + " : " + clip(resp, 120));
        } catch (Exception e) {
            Log.w(TAG, "triggerDeployWebhook", e);
            return new Validation(false, "Webhook Hostinger : "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(line);
            }
        }
        return sb.toString();
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').trim();
        return t.length() <= max ? t : t.substring(0, max - 1) + "…";
    }
}
