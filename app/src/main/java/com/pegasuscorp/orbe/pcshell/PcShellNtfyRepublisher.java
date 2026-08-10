package com.pegasuscorp.orbe.pcshell;

import android.text.TextUtils;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Republication ntfy.sh (snooze « Plus tard »).
 */
public final class PcShellNtfyRepublisher {

    private PcShellNtfyRepublisher() {}

    static void publish(PcShellNtfySnoozeScheduler.SnoozePayload payload) throws Exception {
        if (payload == null || TextUtils.isEmpty(payload.topic)) {
            throw new IllegalArgumentException("Topic ntfy manquant");
        }
        String base = PcShellPrefs.getNtfyBaseUrl();
        String urlStr = base.endsWith("/") ? base + payload.topic : base + "/" + payload.topic;
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(15_000);
        if (!TextUtils.isEmpty(payload.title)) {
            conn.setRequestProperty("Title", payload.title);
        }
        conn.setRequestProperty("Priority", "default");
        conn.setRequestProperty("Tags", "package,arrow_up");
        String actionsJson = payload.actionsJson;
        if (TextUtils.isEmpty(actionsJson)) {
            actionsJson = PcShellNtfyActions.buildUpdateActionsJson(
                    payload.topic, payload.title, payload.message);
        }
        if (!TextUtils.isEmpty(actionsJson)) {
            conn.setRequestProperty("Actions", actionsJson);
        }
        String token = !TextUtils.isEmpty(payload.token)
                ? payload.token
                : PcShellPrefs.getNtfyToken();
        if (!TextUtils.isEmpty(token)) {
            conn.setRequestProperty("Authorization", "Bearer " + token);
        }
        byte[] body = (payload.message != null ? payload.message : "").getBytes(StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode(body.length);
        try (OutputStream out = conn.getOutputStream()) {
            out.write(body);
        }
        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("ntfy HTTP " + code);
        }
        conn.disconnect();
    }
}
