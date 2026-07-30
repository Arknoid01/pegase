package com.pegasuscorp.orbe.tools.knowledge;

import com.pegasuscorp.orbe.tools.ToolTag;

import com.pegasuscorp.orbe.tools.ToolResult;

import com.pegasuscorp.orbe.tools.Tool;
import com.pegasuscorp.orbe.tools.ToolCallback;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Faits encyclopédiques via Wikipedia REST (fr) — 0 clé API.
 * Préférer à {@code search}/Tavily pour définitions et concepts stables.
 */
public final class WikipediaTool implements Tool {

    private static final int EXTRACT_MAX = 700;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    @Override
    public String id() {
        return "wikipedia";
    }

    @Override
    public ToolTag tag() {
        return ToolTag.WIKIPEDIA;
    }

    @Override
    public String description() {
        return "wikipedia(query:str, lang?:str=\"fr\") — Résumé encyclopédique Wikipedia (gratuit, 0 clé). "
                + "Utilise pour « c'est quoi », définitions, concepts, histoire, sciences. "
                + "NE PAS utiliser pour l'actualité, scores du jour, prix — préfère search (Tavily). "
                + "Exemple : {\"tool\":\"wikipedia\",\"params\":{\"query\":\"coefficient de restitution\"}}.";
    }

    @Override
    public void execute(Context ctx, JSONObject params, ToolCallback cb) {
        String query = params != null ? params.optString("query", "").trim() : "";
        if (query.isEmpty() && params != null) {
            query = params.optString("q", "").trim();
        }
        if (query.isEmpty()) {
            cb.onError("Précise le sujet à chercher sur Wikipedia.");
            return;
        }
        String lang = params != null ? params.optString("lang", "fr").trim() : "fr";
        if (lang.isEmpty()) lang = "fr";
        final String q = query;
        final String language = lang;
        IO.execute(() -> {
            try {
                String text = fetchSummary(q, language);
                MAIN.post(() -> cb.onSuccess(ToolResult.text(text)));
            } catch (Exception e) {
                MAIN.post(() -> cb.onError("Wikipedia indisponible : " + e.getMessage()));
            }
        });
    }

    /** Logique réseau — exposée pour tests. */
    static String fetchSummary(String query, String lang) throws Exception {
        String host = lang + ".wikipedia.org";
        String searchUrl = "https://" + host + "/w/rest.php/v1/search/page?q="
                + WikiHttp.encodeQuery(query) + "&limit=1";
        JSONObject search = new JSONObject(WikiHttp.get(searchUrl));
        JSONArray pages = search.optJSONArray("pages");
        if (pages == null || pages.length() == 0) {
            return "Je n'ai rien trouvé sur Wikipedia pour « " + query + " ».";
        }
        JSONObject page = pages.getJSONObject(0);
        String key = page.optString("key", "");
        if (TextUtils.isEmpty(key)) {
            key = page.optString("title", query);
        }
        String summaryUrl = "https://" + host + "/api/rest_v1/page/summary/"
                + WikiHttp.encodeWikiTitle(key);
        JSONObject summary = new JSONObject(WikiHttp.get(summaryUrl));
        String title = summary.optString("title", key);
        String extract = summary.optString("extract", "");
        if (TextUtils.isEmpty(extract)) {
            extract = summary.optString("description", "");
        }
        if (TextUtils.isEmpty(extract)) {
            return "Article « " + title + " » trouvé, mais sans résumé utilisable.";
        }
        extract = WikiHttp.truncate(extract, EXTRACT_MAX);
        // Soft hyphens Wikipedia → tiret dur (évite « soixanteetunième » à l'affichage)
        extract = extract.replace('\u00AD', '-').replaceAll("-{2,}", "-");
        return "Wikipedia — " + title + " :\n" + extract;
    }
}
