package com.pegasuscorp.orbe.tools.knowledge;

import android.content.Context;

import com.pegasuscorp.orbe.chat.ApiKeyStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Appel Tavily avec plusieurs sources pour recoupement.
 */
public final class TavilySearchService {

    private static final String ENDPOINT = "https://api.tavily.com/search";

    public static final class SourceSnippet {
        public final String title;
        public final String site;
        public final String content;

        SourceSnippet(String title, String site, String content) {
            this.title = title != null ? title : "";
            this.site = site != null ? site : "";
            this.content = content != null ? content : "";
        }
    }

    public static final class Bundle {
        public final String query;
        public final String engineAnswer;
        public final List<SourceSnippet> sources;

        Bundle(String query, String engineAnswer, List<SourceSnippet> sources) {
            this.query = query;
            this.engineAnswer = engineAnswer;
            this.sources = sources;
        }

        public boolean hasContent() {
            if (engineAnswer != null && !engineAnswer.isEmpty()) return true;
            for (SourceSnippet s : sources) {
                if (s.content != null && !s.content.isEmpty()) return true;
            }
            return false;
        }

        /** Réponse de secours si la synthèse LLM échoue ou hors boucle agentique. */
        public String fallbackSpeech() {
            if (!hasContent()) {
                return "Je n'ai pas trouvé d'information fiable sur « " + query + " ».";
            }
            StringBuilder sb = new StringBuilder("D'après ce que j'ai trouvé sur le web, ");
            if (engineAnswer != null && !engineAnswer.isEmpty()) {
                sb.append(engineAnswer);
            } else {
                int limit = Math.min(3, sources.size());
                for (int i = 0; i < limit; i++) {
                    if (i > 0) sb.append(' ');
                    sb.append(sources.get(i).content);
                }
            }
            return sb.toString().trim();
        }

        private static final int MAX_SNIPPET_CHARS = 360;

        /**
         * Extraits web structurés pour la synthèse LLM agentique (une seule passe après Tavily).
         */
        public String toLlmContext(String userQuestion) {
            StringBuilder sb = new StringBuilder();
            sb.append("[Résultats recherche web — croise les sources, signale les contradictions]\n");
            if (userQuestion != null && !userQuestion.isEmpty()) {
                sb.append("Question : ").append(userQuestion.trim()).append('\n');
            }
            if (query != null && !query.isEmpty()) {
                sb.append("Requête Tavily : ").append(query.trim()).append('\n');
            }
            if (engineAnswer != null && !engineAnswer.isEmpty()) {
                sb.append("\nSynthèse moteur : ").append(truncate(engineAnswer)).append('\n');
            }
            int i = 1;
            for (SourceSnippet src : sources) {
                if (src.content == null || src.content.isEmpty()) continue;
                sb.append("\nSource ").append(i++).append(" — ");
                if (!src.title.isEmpty()) sb.append(src.title);
                if (!src.site.isEmpty()) sb.append(" (").append(src.site).append(")");
                sb.append(" : ").append(truncate(src.content));
            }
            return sb.toString().trim();
        }

        private static String truncate(String text) {
            if (text == null) return "";
            String t = text.trim();
            if (t.length() <= MAX_SNIPPET_CHARS) return t;
            return t.substring(0, MAX_SNIPPET_CHARS - 1).trim() + "…";
        }
    }

    private TavilySearchService() {}

    public static Bundle search(Context ctx, String query) throws Exception {
        String key = ApiKeyStore.getTavilyKey(ctx);
        if (key.isEmpty()) {
            throw new IllegalStateException("Clé Tavily manquante");
        }

        JSONObject body = new JSONObject();
        body.put("query", query);
        body.put("search_depth", "advanced");
        body.put("max_results", 5);
        body.put("include_answer", true);
        body.put("include_raw_content", false);
        body.put("topic", "general");
        body.put("exclude_domains", new JSONArray()
                .put("facebook.com")
                .put("twitter.com")
                .put("x.com")
                .put("tiktok.com")
                .put("reddit.com")
                .put("instagram.com")
                .put("threads.net"));

        HttpURLConnection conn = (HttpURLConnection) new URL(ENDPOINT).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + key);
        conn.setDoOutput(true);
        conn.setConnectTimeout(12_000);
        conn.setReadTimeout(28_000);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        BufferedReader br = new BufferedReader(new InputStreamReader(
                code < 400 ? conn.getInputStream() : conn.getErrorStream(),
                StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();

        if (code >= 400) {
            throw new IllegalStateException("Tavily erreur " + code);
        }

        JSONObject res = new JSONObject(sb.toString());
        String answer = res.optString("answer", "").trim();
        List<SourceSnippet> sources = new ArrayList<>();
        JSONArray results = res.optJSONArray("results");
        if (results != null) {
            for (int i = 0; i < results.length(); i++) {
                JSONObject r = results.getJSONObject(i);
                String title = r.optString("title", "").trim();
                String url = r.optString("url", "").trim();
                String content = r.optString("content", "").trim();
                if (content.isEmpty()) continue;
                sources.add(new SourceSnippet(title, siteFromUrl(url), content));
            }
        }
        return new Bundle(query, answer, sources);
    }

    private static String siteFromUrl(String url) {
        if (url == null || url.isEmpty()) return "";
        try {
            String host = new URI(url).getHost();
            if (host == null) return "";
            if (host.startsWith("www.")) host = host.substring(4);
            return host;
        } catch (Exception e) {
            return "";
        }
    }
}
