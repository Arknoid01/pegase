package com.pegasuscorp.orbe.orion;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.pegasuscorp.orbe.chat.ApiKeyStore;
import com.pegasuscorp.orbe.tools.HttpJson;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Client graphe fichiers pod ({@code /graph}, {@code /related}, {@code /symbols}, {@code /reindex}) — best-effort.
 * L1 imports · L2 ids/classes · L3 symboles JS ({@code sym:name}).
 */
public final class OrionGraphClient {

    private static final String TAG = "OrionGraph";
    private static final int TIMEOUT_MS = 8_000;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "orion-graph");
        t.setDaemon(true);
        return t;
    });

    private OrionGraphClient() {}

    /** Après écriture pod — reindex un fichier, ne bloque jamais. */
    public static void reindexBestEffort(Context ctx, String filename) {
        if (ctx == null || TextUtils.isEmpty(filename)) return;
        if (!PodFileClient.isOnline()) return;
        if (!isGraphable(filename)) return;
        final Context app = ctx.getApplicationContext();
        final String name = basename(filename);
        IO.execute(() -> {
            try {
                reindex(app, name);
            } catch (Exception e) {
                Log.w(TAG, "reindexBestEffort", e);
            }
        });
    }

    /** POST /reindex — synchrone, hors UI. Retourne true si ok. */
    public static boolean reindex(Context ctx, String filename) {
        if (ctx == null || TextUtils.isEmpty(filename)) return false;
        if (!PodFileClient.isOnline()) return false;
        String project = OrionProjectStore.get(ctx).getActiveProject();
        if (TextUtils.isEmpty(project)) return false;
        String base = OrionStateStore.get().getFileServerUrl();
        if (TextUtils.isEmpty(base)) return false;
        try {
            String file = basename(filename);
            String url = base + "/reindex?file=" + enc(file)
                    + "&project=" + enc(sanitize(project));
            JSONObject json = post(ctx, url);
            return json != null && json.optBoolean("ok", true);
        } catch (Exception e) {
            Log.w(TAG, "reindex " + filename, e);
            return false;
        }
    }

    /**
     * GET /symbols — défini dans / référencé dans. Listes vides si indisponible.
     */
    public static JSONObject symbols(Context ctx, String symbolName) {
        if (ctx == null || TextUtils.isEmpty(symbolName)) return null;
        if (!PodFileClient.isOnline()) return null;
        String project = OrionProjectStore.get(ctx).getActiveProject();
        if (TextUtils.isEmpty(project)) return null;
        String base = OrionStateStore.get().getFileServerUrl();
        if (TextUtils.isEmpty(base)) return null;
        try {
            String url = base + "/symbols?name=" + enc(symbolName.trim())
                    + "&project=" + enc(sanitize(project));
            String token = ApiKeyStore.getOrionToken(ctx);
            Map<String, String> headers = new java.util.HashMap<>();
            if (!TextUtils.isEmpty(token)) {
                headers.put("Authorization", "Bearer " + token);
            }
            return HttpJson.get(url, headers, TIMEOUT_MS, TIMEOUT_MS);
        } catch (Exception e) {
            Log.w(TAG, "symbols " + symbolName, e);
            return null;
        }
    }

    /**
     * GET /related — fichiers liés (1 hop, L1+L2+L3). Liste vide si indisponible.
     * N'inclut jamais d'échec bloquant.
     */
    public static List<String> related(Context ctx, String filename) {
        if (ctx == null || TextUtils.isEmpty(filename)) return Collections.emptyList();
        if (!PodFileClient.isOnline()) return Collections.emptyList();
        String project = OrionProjectStore.get(ctx).getActiveProject();
        if (TextUtils.isEmpty(project)) return Collections.emptyList();
        String base = OrionStateStore.get().getFileServerUrl();
        if (TextUtils.isEmpty(base)) return Collections.emptyList();
        try {
            String file = basename(filename);
            String url = base + "/related?file=" + enc(file)
                    + "&project=" + enc(sanitize(project));
            String token = ApiKeyStore.getOrionToken(ctx);
            Map<String, String> headers = new java.util.HashMap<>();
            if (!TextUtils.isEmpty(token)) {
                headers.put("Authorization", "Bearer " + token);
            }
            JSONObject json = HttpJson.get(url, headers, TIMEOUT_MS, TIMEOUT_MS);
            if (json == null) return Collections.emptyList();
            JSONArray arr = json.optJSONArray("related");
            if (arr == null || arr.length() == 0) {
                return Collections.singletonList(file);
            }
            List<String> out = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                String n = arr.optString(i, "").trim();
                if (!n.isEmpty() && !out.contains(n)) out.add(n);
            }
            return out;
        } catch (Exception e) {
            Log.w(TAG, "related " + filename, e);
            return Collections.emptyList();
        }
    }

    static boolean isGraphable(String filename) {
        String n = basename(filename).toLowerCase(Locale.ROOT);
        return n.endsWith(".js") || n.endsWith(".mjs")
                || n.endsWith(".html") || n.endsWith(".htm")
                || n.endsWith(".css");
    }

    private static JSONObject post(Context ctx, String url) throws Exception {
        String token = ApiKeyStore.getOrionToken(ctx);
        Map<String, String> headers = new java.util.HashMap<>();
        if (!TextUtils.isEmpty(token)) {
            headers.put("Authorization", "Bearer " + token);
        }
        return HttpJson.postJson(url, headers, new JSONObject(), TIMEOUT_MS, TIMEOUT_MS);
    }

    private static String basename(String path) {
        if (path == null) return "";
        String p = path.replace('\\', '/').trim();
        int slash = p.lastIndexOf('/');
        return slash >= 0 ? p.substring(slash + 1) : p;
    }

    private static String sanitize(String s) {
        if (s == null) return "";
        return s.trim().replaceAll("[^a-zA-Z0-9._\\-]", "_");
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return s == null ? "" : s;
        }
    }
}
