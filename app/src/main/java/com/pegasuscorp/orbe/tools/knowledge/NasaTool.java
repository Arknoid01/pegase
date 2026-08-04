package com.pegasuscorp.orbe.tools.knowledge;

import com.pegasuscorp.orbe.tools.ToolTag;

import com.pegasuscorp.orbe.tools.ToolResult;

import com.pegasuscorp.orbe.tools.Tool;
import com.pegasuscorp.orbe.tools.ToolCallback;

import android.content.Context;
import com.pegasuscorp.orbe.chat.ApiKeyStore;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Informations spatiales via l'API de la NASA.
 * Supporte principalement l'APOD (Astronomy Picture of the Day).
 */
public final class NasaTool implements Tool {

    private final ExecutorService io = Executors.newSingleThreadExecutor();

    @Override public String id() { return "nasa"; }

    @Override public ToolTag tag() { return ToolTag.NASA; }

    @Override
    public String description() {
        return "nasa() — Image astronomique du jour NASA (APOD) + explication.";
    }

    @Override
    public void execute(Context ctx, JSONObject params, ToolCallback cb) {
        String key = ApiKeyStore.getNasaApiKey(ctx);
        if (key.isEmpty()) {
            key = "DEMO_KEY"; // La NASA autorise une démo limitée
        }

        final String finalKey = key;
        io.execute(() -> {
            try {
                String urlStr = "https://api.nasa.gov/planetary/apod?api_key=" + finalKey;
                HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
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
                    cb.onError("Erreur NASA " + code);
                    return;
                }

                JSONObject res = new JSONObject(sb.toString());
                String title       = res.optString("title", "Sans titre");
                String explanation = res.optString("explanation", "");
                String imageUrl    = res.optString("url", "");      // URL image du jour
                String hdUrl       = res.optString("hdurl", imageUrl); // HD si dispo
                String thumbUrl    = res.optString("thumbnail_url", "");
                String mediaType   = res.optString("media_type", "image");

                // Tronque l'explication pour ne pas saturer le contexte
                if (explanation.length() > 800) explanation = explanation.substring(0, 800);

                // Préférer l'URL standard (souvent ~1 Mo) — le HD peut échouer
                // au téléchargement / décodage sur mobile.
                String displayUrl = "";
                if ("image".equals(mediaType)) {
                    if (!imageUrl.isEmpty()) displayUrl = imageUrl;
                    else if (!hdUrl.isEmpty()) displayUrl = hdUrl;
                } else if ("video".equals(mediaType) && !thumbUrl.isEmpty()) {
                    displayUrl = thumbUrl;
                } else if (!imageUrl.isEmpty() && imageUrl.startsWith("http")) {
                    displayUrl = imageUrl;
                }
                if (displayUrl.startsWith("http://")) {
                    displayUrl = "https://" + displayUrl.substring(7);
                }

                String summary = "NASA APOD du jour (traduis et résume en 2-3 phrases en français oral) :\n"
                        + "Titre : " + title + "\n"
                        + "Explication : " + explanation;

                if (!displayUrl.isEmpty()) {
                    cb.onSuccess(ToolResult.imageUrl(summary, displayUrl));
                } else {
                    cb.onSuccess(ToolResult.text(summary));
                }

            } catch (Exception e) {
                cb.onError("Impossible de contacter la NASA : " + e.getMessage());
            }
        });
    }
}
