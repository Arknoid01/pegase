package com.pegasuscorp.orbe.tools.knowledge;

import com.pegasuscorp.orbe.tools.ToolTag;

import com.pegasuscorp.orbe.tools.ToolResult;

import com.pegasuscorp.orbe.tools.Tool;
import com.pegasuscorp.orbe.tools.ToolCallback;

import android.content.Context;

import com.pegasuscorp.orbe.chat.ApiKeyStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Actualités via NewsAPI (100 req/jour gratuit, articles en français).
 * Clé à saisir dans Paramètres → Clés API.
 *
 * Paramètres :
 *   query : String — sujet à rechercher (optionnel, défaut = titres du jour)
 *   count : int    — nombre d'articles à résumer (défaut 3, max 5)
 */
public final class NewsTool implements Tool {

    private final ExecutorService io = Executors.newSingleThreadExecutor();

    @Override public String id() { return "news"; }

    @Override public ToolTag tag() { return ToolTag.NEWS; }

    @Override
    public String description() {
        return "news(query?:str, count?:int) — Actualités FR. "
                + "query=sujet optionnel ; count=nb articles (défaut 3).";
    }

    @Override
    public void execute(Context ctx, JSONObject params, ToolCallback cb) {
        String key = ApiKeyStore.getNewsApiKey(ctx);
        if (key.isEmpty()) {
            cb.onError("Clé NewsAPI manquante — saisis-la dans les paramètres.");
            return;
        }

        String query = params.optString("query", "").trim();
        int count = Math.max(1, Math.min(5, params.optInt("count", 3)));

        io.execute(() -> {
            try {
                String endpoint;
                if (query.isEmpty()) {
                    // Titres du jour en français
                    endpoint = "https://newsapi.org/v2/top-headlines"
                            + "?country=fr&language=fr&pageSize=" + count
                            + "&apiKey=" + key;
                } else {
                    // Recherche sur un sujet
                    endpoint = "https://newsapi.org/v2/everything"
                            + "?q=" + java.net.URLEncoder.encode(query, "UTF-8")
                            + "&language=fr&sortBy=publishedAt&pageSize=" + count
                            + "&apiKey=" + key;
                }

                HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);

                int code = conn.getResponseCode();
                BufferedReader br = new BufferedReader(new InputStreamReader(
                        code < 400 ? conn.getInputStream() : conn.getErrorStream(), "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();

                if (code >= 400) {
                    cb.onError("NewsAPI erreur " + code);
                    return;
                }

                JSONObject json = new JSONObject(sb.toString());
                JSONArray articles = json.getJSONArray("articles");

                if (articles.length() == 0) {
                    cb.onSuccess(ToolResult.text(query.isEmpty()
                            ? "Aucune actualité disponible pour le moment."
                            : "Aucune actualité trouvée sur « " + query + " »."));
                    return;
                }

                StringBuilder result = new StringBuilder();
                int total = Math.min(count, articles.length());
                for (int i = 0; i < total; i++) {
                    JSONObject a = articles.getJSONObject(i);
                    String title = a.optString("title", "");
                    String desc  = a.optString("description", "");
                    String source = a.optJSONObject("source") != null
                            ? a.optJSONObject("source").optString("name", "") : "";
                    if (!title.isEmpty()) {
                        result.append(i + 1).append(". ");
                        if (!source.isEmpty()) result.append("(").append(source).append(") ");
                        result.append(title);
                        if (!desc.isEmpty() && !desc.equals(title)) {
                            result.append(" — ").append(desc);
                        }
                        result.append(". ");
                    }
                }
                cb.onSuccess(ToolResult.text(result.toString().trim()));

            } catch (Exception e) {
                cb.onError("Impossible de récupérer les actualités : " + e.getMessage());
            }
        });
    }
}
