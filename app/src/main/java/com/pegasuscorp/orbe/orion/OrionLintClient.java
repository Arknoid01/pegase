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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Client GET {@code /lint} du fileserver pod — best-effort, hors UI thread.
 */
public final class OrionLintClient {

    private static final String TAG = "OrionLint";
    private static final int TIMEOUT_MS = 8_000;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "orion-lint");
        t.setDaemon(true);
        return t;
    });

    /** Derniers rapports visibles (fichier → rapport), hors tool_missing. */
    private static final ConcurrentHashMap<String, LintReport> CACHE = new ConcurrentHashMap<>();

    private OrionLintClient() {}

    public static LintReport getCached(String filename) {
        if (TextUtils.isEmpty(filename)) return null;
        return CACHE.get(key(filename));
    }

    public static void clearCache() {
        CACHE.clear();
    }

    /**
     * Lint synchrone (appeler hors thread principal).
     * Retourne null si hors ligne / indisponible / tool_missing (rien à afficher).
     */
    public static LintReport check(Context ctx, String filename) {
        if (ctx == null || TextUtils.isEmpty(filename)) return null;
        if (!PodFileClient.isOnline()) return null;
        if (!isLintable(filename)) return null;
        String project = OrionProjectStore.get(ctx).getActiveProject();
        if (TextUtils.isEmpty(project)) return null;
        String base = OrionStateStore.get().getFileServerUrl();
        if (TextUtils.isEmpty(base)) return null;
        try {
            String file = basename(filename);
            String url = base + "/lint?file=" + enc(file) + "&project=" + enc(sanitize(project));
            String token = ApiKeyStore.getOrionToken(ctx);
            Map<String, String> headers = new java.util.HashMap<>();
            if (!TextUtils.isEmpty(token)) {
                headers.put("Authorization", "Bearer " + token);
            }
            JSONObject json = HttpJson.get(url, headers, TIMEOUT_MS, TIMEOUT_MS);
            LintReport report = parse(json, file);
            if (report == null || report.toolMissing || !report.hasVisibleIssues()) {
                CACHE.remove(key(file));
                return null;
            }
            CACHE.put(key(file), report);
            return report;
        } catch (Exception e) {
            Log.w(TAG, "check " + filename, e);
            return null;
        }
    }

    /**
     * Après écriture pod — ne bloque jamais l'appelant.
     * Notifie {@link OrionFileStore} si un rapport visible arrive.
     */
    public static void checkBestEffort(Context ctx, String filename) {
        if (ctx == null || TextUtils.isEmpty(filename)) return;
        if (!PodFileClient.isOnline()) return;
        if (!isLintable(filename)) return;
        final Context app = ctx.getApplicationContext();
        final String name = basename(filename);
        IO.execute(() -> {
            try {
                LintReport r = check(app, name);
                if (r != null && r.hasVisibleIssues()) {
                    OrionFileStore.get().notifyLintUpdated();
                } else {
                    // Effacer un ancien badge si le fichier est redevenu propre
                    if (CACHE.remove(key(name)) != null) {
                        OrionFileStore.get().notifyLintUpdated();
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "checkBestEffort", e);
            }
        });
    }

    static LintReport parse(JSONObject json, String fallbackFile) {
        if (json == null) return null;
        boolean toolMissing = json.optBoolean("tool_missing", false);
        String file = json.optString("file", fallbackFile);
        String tool = json.optString("tool", "");
        boolean ok = json.optBoolean("ok", true);
        List<LintReport.LintIssue> issues = new ArrayList<>();
        JSONArray arr = json.optJSONArray("issues");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                issues.add(new LintReport.LintIssue(
                        o.optInt("line", 0),
                        o.optInt("column", 0),
                        o.optString("severity", "warning"),
                        o.optString("rule", ""),
                        o.optString("message", "")));
            }
        }
        return new LintReport(file, tool, ok, toolMissing, issues);
    }

    static boolean isLintable(String filename) {
        String n = basename(filename).toLowerCase(Locale.ROOT);
        return n.endsWith(".js") || n.endsWith(".mjs")
                || n.endsWith(".html") || n.endsWith(".htm")
                || n.endsWith(".css");
    }

    private static String key(String filename) {
        return basename(filename).toLowerCase(Locale.ROOT);
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
