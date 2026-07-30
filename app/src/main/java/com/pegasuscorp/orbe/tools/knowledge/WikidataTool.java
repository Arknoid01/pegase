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
 * Entités structurées via Wikidata (wbsearchentities) — 0 clé API.
 * Utile pour « qui a inventé… », dates, identifiants Q.
 */
public final class WikidataTool implements Tool {

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    @Override
    public String id() {
        return "wikidata";
    }

    @Override
    public ToolTag tag() {
        return ToolTag.WIKIDATA;
    }

    @Override
    public String description() {
        return "wikidata(query:str, lang?:str=\"fr\") — Fiche entité Wikidata (gratuit, 0 clé). "
                + "Utilise pour « qui a inventé », « qui a créé », dates, personnes, inventions. "
                + "Pour une définition longue, préfère wikipedia. "
                + "NE PAS utiliser pour l'actualité — préfère search (Tavily). "
                + "Exemple : {\"tool\":\"wikidata\",\"params\":{\"query\":\"HTML\"}}.";
    }

    @Override
    public void execute(Context ctx, JSONObject params, ToolCallback cb) {
        String query = params != null ? params.optString("query", "").trim() : "";
        if (query.isEmpty() && params != null) {
            query = params.optString("q", "").trim();
        }
        if (query.isEmpty()) {
            cb.onError("Précise l'entité à chercher sur Wikidata.");
            return;
        }
        String lang = params != null ? params.optString("lang", "fr").trim() : "fr";
        if (lang.isEmpty()) lang = "fr";
        final String q = query;
        final String language = lang;
        IO.execute(() -> {
            try {
                String text = fetchEntity(q, language);
                MAIN.post(() -> cb.onSuccess(ToolResult.text(text)));
            } catch (Exception e) {
                MAIN.post(() -> cb.onError("Wikidata indisponible : " + e.getMessage()));
            }
        });
    }

    static String fetchEntity(String query, String lang) throws Exception {
        String url = "https://www.wikidata.org/w/api.php"
                + "?action=wbsearchentities"
                + "&search=" + WikiHttp.encodeQuery(query)
                + "&language=" + WikiHttp.encodeQuery(lang)
                + "&uselang=" + WikiHttp.encodeQuery(lang)
                + "&limit=3"
                + "&format=json"
                + "&origin=*";
        JSONObject root = new JSONObject(WikiHttp.get(url));
        JSONArray results = root.optJSONArray("search");
        if (results == null || results.length() == 0) {
            return "Je n'ai rien trouvé sur Wikidata pour « " + query + " ».";
        }
        StringBuilder sb = new StringBuilder("Wikidata :\n");
        int n = Math.min(3, results.length());
        for (int i = 0; i < n; i++) {
            JSONObject hit = results.getJSONObject(i);
            String label = hit.optString("label", "");
            String desc = hit.optString("description", "");
            String id = hit.optString("id", "");
            if (TextUtils.isEmpty(label)) continue;
            if (i > 0) sb.append('\n');
            sb.append("- ").append(label);
            if (!TextUtils.isEmpty(id)) sb.append(" (").append(id).append(")");
            if (!TextUtils.isEmpty(desc)) sb.append(" : ").append(desc);
        }
        return sb.toString().trim();
    }
}
