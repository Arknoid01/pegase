package com.pegasuscorp.orbe.orion;

import android.content.Context;
import android.text.TextUtils;

import com.pegasuscorp.orbe.chat.ApiKeyStore;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Client HTTP du serveur fichiers pod (port 3000) — preview live + sync écritures.
 */
public final class PodFileClient {

    private static final int TIMEOUT_MS = 12_000;

    private PodFileClient() {}

    public static boolean isOnline() {
        OrionStateStore s = OrionStateStore.get();
        OrionStatus st = s.getStatus();
        return (st == OrionStatus.READY || st == OrionStatus.BUSY)
                && !TextUtils.isEmpty(s.getPodId());
    }

    public static String previewUrl(String project, String mainFile) {
        String base = OrionStateStore.get().getFileServerUrl();
        if (TextUtils.isEmpty(base)) return "";
        String proj = project == null ? "" : project.trim().replace(" ", "-");
        String file = mainFile == null || mainFile.isEmpty() ? "index.html" : mainFile;
        return base + "/projects/" + proj + "/" + file;
    }

    /** Écriture best-effort (ne bloque pas la génération locale si le pod refuse). */
    public static void writeBestEffort(Context ctx, String filename, String content) {
        if (!isOnline() || TextUtils.isEmpty(filename)) return;
        String project = OrionProjectStore.get(ctx).getActiveProject();
        if (TextUtils.isEmpty(project)) return;
        try {
            writeFile(ctx, project, filename, content);
        } catch (Exception ignored) {
        }
    }

    public static void writeFile(Context ctx, String project, String filename, String content)
            throws Exception {
        String base = OrionStateStore.get().getFileServerUrl();
        if (TextUtils.isEmpty(base)) throw new IllegalStateException("Serveur fichiers hors ligne");
        String path = "/projects/" + sanitize(project) + "/" + sanitize(filename);
        String token = ApiKeyStore.getOrionToken(ctx);
        HttpURLConnection conn = (HttpURLConnection) new URL(base + path).openConnection();
        conn.setRequestMethod("PUT");
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
        conn.setRequestProperty("User-Agent", "Orbe-Pegase/1.0");
        if (!TextUtils.isEmpty(token)) {
            conn.setRequestProperty("Authorization", "Bearer " + token);
        }
        byte[] bytes = (content != null ? content : "").getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(bytes);
        }
        int code = conn.getResponseCode();
        conn.disconnect();
        if (code >= 400) {
            throw new IllegalStateException("PUT fichiers pod HTTP " + code);
        }
    }

    private static String sanitize(String s) {
        if (s == null) return "";
        String t = s.replace('\\', '/').trim();
        int slash = t.lastIndexOf('/');
        if (slash >= 0) t = t.substring(slash + 1);
        return t.replaceAll("[^a-zA-Z0-9._\\-]", "_");
    }
}
