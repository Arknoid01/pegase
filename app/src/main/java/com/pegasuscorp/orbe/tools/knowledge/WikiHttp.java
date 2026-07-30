package com.pegasuscorp.orbe.tools.knowledge;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * GET HTTP partagé Wikipedia / Wikidata — User-Agent Wikimedia, 0 clé API.
 */
public final class WikiHttp {

    public static final String USER_AGENT =
            "Pegase/1.0 (Orbe Android Assistant; contact@pegasuscorp.fr)";

    private static final int CONNECT_MS = 8_000;
    private static final int READ_MS = 10_000;

    /** Injectable pour les tests. */
    public interface Fetcher {
        String get(String url) throws Exception;
    }

    private static volatile Fetcher overrideForTests;

    private WikiHttp() {}

    public static void setFetcherForTests(Fetcher fetcher) {
        overrideForTests = fetcher;
    }

    public static String get(String url) throws Exception {
        Fetcher override = overrideForTests;
        if (override != null) return override.get(url);
        return getReal(url);
    }

    private static String getReal(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(CONNECT_MS);
        conn.setReadTimeout(READ_MS);
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("Accept", "application/json");
        int code = conn.getResponseCode();
        BufferedReader br = new BufferedReader(new InputStreamReader(
                code < 400 ? conn.getInputStream() : conn.getErrorStream(),
                StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        if (code >= 400) {
            throw new IllegalStateException("HTTP " + code + " : " + truncate(sb.toString(), 120));
        }
        return sb.toString();
    }

    /** Encode un titre wiki (espaces → {@code _}, puis URL-encode). */
    public static String encodeWikiTitle(String title) {
        if (title == null) return "";
        String withUnderscores = title.trim().replace(' ', '_');
        try {
            return URLEncoder.encode(withUnderscores, "UTF-8");
        } catch (Exception e) {
            return withUnderscores;
        }
    }

    public static String encodeQuery(String query) {
        if (query == null) return "";
        try {
            return URLEncoder.encode(query.trim(), "UTF-8");
        } catch (Exception e) {
            return query.trim();
        }
    }

    public static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, Math.max(0, max - 1)).trim() + "…";
    }
}
