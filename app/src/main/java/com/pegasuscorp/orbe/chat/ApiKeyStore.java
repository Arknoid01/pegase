package com.pegasuscorp.orbe.chat;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

/**
 * Clés API cloud stockées sur l'appareil (SharedPreferences privées).
 * Saisis-les dans Tiroir → ⚙️ → Clés API.
 */
public final class ApiKeyStore {

    private static final String PREFS = "orbe_api_keys";
    private static final String KEY_GROQ   = "groq_api_key";
    private static final String KEY_GEMINI = "gemini_api_key";
    private static final String KEY_TAVILY = "tavily_api_key";
    private static final String KEY_NEWS   = "newsapi_api_key";
    private static final String KEY_NASA   = "nasa_api_key";
    private static final String KEY_API_FOOTBALL = "api_football_key";
    private static final String KEY_SPOTIFY_CLIENT_ID = "spotify_client_id";

    private ApiKeyStore() {}

    public static String getGroqKey(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_GROQ, "").trim();
    }
    public static void setGroqKey(Context context, String key) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_GROQ, key == null ? "" : key.trim()).apply();
    }

    private static final String KEY_CEREBRAS = "cerebras_api_key";
    private static final String KEY_OPENROUTER = "openrouter_api_key";

    public static String getCerebrasKey(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_CEREBRAS, "").trim();
    }
    public static void setCerebrasKey(Context context, String key) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_CEREBRAS, key == null ? "" : key.trim()).apply();
    }
    public static boolean hasCerebrasKey(Context context) {
        return !TextUtils.isEmpty(getCerebrasKey(context));
    }

    public static String getOpenRouterKey(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_OPENROUTER, "").trim();
    }
    public static void setOpenRouterKey(Context context, String key) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_OPENROUTER, key == null ? "" : key.trim()).apply();
    }
    public static boolean hasOpenRouterKey(Context context) {
        return !TextUtils.isEmpty(getOpenRouterKey(context));
    }

    public static String getGeminiKey(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_GEMINI, "").trim();
    }
    public static void setGeminiKey(Context context, String key) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_GEMINI, key == null ? "" : key.trim()).apply();
    }

    public static String getTavilyKey(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_TAVILY, "").trim();
    }
    public static void setTavilyKey(Context context, String key) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_TAVILY, key == null ? "" : key.trim()).apply();
    }

    public static String getNewsApiKey(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_NEWS, "").trim();
    }
    public static void setNewsApiKey(Context context, String key) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_NEWS, key == null ? "" : key.trim()).apply();
    }

    public static String getNasaApiKey(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_NASA, "").trim();
    }
    public static void setNasaApiKey(Context context, String key) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_NASA, key == null ? "" : key.trim()).apply();
    }

    public static String getApiFootballKey(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_API_FOOTBALL, "").trim();
    }
    public static void setApiFootballKey(Context context, String key) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_API_FOOTBALL, key == null ? "" : key.trim()).apply();
    }

    public static boolean hasGroqKey(Context context) {
        return !TextUtils.isEmpty(getGroqKey(context));
    }
    public static boolean hasGeminiKey(Context context) {
        return !TextUtils.isEmpty(getGeminiKey(context));
    }
    public static boolean hasTavilyKey(Context context) {
        return !TextUtils.isEmpty(getTavilyKey(context));
    }
    public static boolean hasNewsApiKey(Context context) {
        return !TextUtils.isEmpty(getNewsApiKey(context));
    }
    public static boolean hasNasaApiKey(Context context) {
        return !TextUtils.isEmpty(getNasaApiKey(context));
    }
    public static boolean hasApiFootballKey(Context context) {
        return !TextUtils.isEmpty(getApiFootballKey(context));
    }

    public static String getSpotifyClientId(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_SPOTIFY_CLIENT_ID, "").trim();
    }
    public static void setSpotifyClientId(Context context, String clientId) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_SPOTIFY_CLIENT_ID, clientId == null ? "" : clientId.trim()).apply();
    }
    public static boolean hasSpotifyClientId(Context context) {
        return !TextUtils.isEmpty(getSpotifyClientId(context));
    }

    // --- Orion / RunPod ---
    private static final String KEY_RUNPOD = "runpod_api_key";
    private static final String KEY_ORION_TOKEN = "orion_token";

    public static String getRunpodApiKey(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_RUNPOD, "").trim();
    }

    public static void setRunpodApiKey(Context context, String key) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_RUNPOD, key == null ? "" : key.trim()).apply();
    }

    public static boolean hasRunpodApiKey(Context context) {
        return !TextUtils.isEmpty(getRunpodApiKey(context));
    }

    /** Token auth Ollama / ORION_TOKEN transmis au pod. */
    public static String getOrionToken(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_ORION_TOKEN, "").trim();
    }

    public static void setOrionToken(Context context, String token) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_ORION_TOKEN, token == null ? "" : token.trim()).apply();
    }

    public static boolean hasOrionToken(Context context) {
        return !TextUtils.isEmpty(getOrionToken(context));
    }

    // --- GitHub / Hostinger (commit distant avec validation) ---
    private static final String KEY_GITHUB_TOKEN = "github_api_token";
    private static final String KEY_GITHUB_REPO = "github_repo"; // owner/repo
    private static final String KEY_GITHUB_BRANCH = "github_branch";
    private static final String KEY_HOSTINGER_TOKEN = "hostinger_api_token";
    private static final String KEY_HOSTINGER_WEBHOOK = "hostinger_deploy_webhook";

    public static String getGithubToken(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_GITHUB_TOKEN, "").trim();
    }

    public static void setGithubToken(Context context, String token) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_GITHUB_TOKEN, token == null ? "" : token.trim()).apply();
    }

    public static boolean hasGithubToken(Context context) {
        return !TextUtils.isEmpty(getGithubToken(context));
    }

    /** Format attendu : {@code owner/repo}. */
    public static String getGithubRepo(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_GITHUB_REPO, "").trim();
    }

    public static void setGithubRepo(Context context, String repo) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_GITHUB_REPO, repo == null ? "" : repo.trim()).apply();
    }

    public static String getGithubBranch(Context context) {
        String b = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_GITHUB_BRANCH, "main").trim();
        return b.isEmpty() ? "main" : b;
    }

    public static void setGithubBranch(Context context, String branch) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_GITHUB_BRANCH,
                        branch == null || branch.trim().isEmpty() ? "main" : branch.trim())
                .apply();
    }

    public static String getHostingerToken(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_HOSTINGER_TOKEN, "").trim();
    }

    public static void setHostingerToken(Context context, String token) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_HOSTINGER_TOKEN, token == null ? "" : token.trim()).apply();
    }

    public static boolean hasHostingerToken(Context context) {
        return !TextUtils.isEmpty(getHostingerToken(context));
    }

    /** Webhook de déploiement Hostinger (optionnel, après commit GitHub). */
    public static String getHostingerWebhook(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_HOSTINGER_WEBHOOK, "").trim();
    }

    public static void setHostingerWebhook(Context context, String url) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_HOSTINGER_WEBHOOK, url == null ? "" : url.trim()).apply();
    }

    // --- Localisation de l'utilisateur (pour la météo) ---
    private static final String KEY_USER_CITY   = "user_city";
    private static final String KEY_USER_COORDS = "user_coords"; // "lat,lon"
    private static final String KEY_USER_COORDS_UPDATED_MS = "user_coords_updated_ms";

    public static String getUserCity(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_USER_CITY, "").trim();
    }
    public static void setUserCity(Context context, String city) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_USER_CITY, city == null ? "" : city.trim()).apply();
    }
    public static String getUserCoords(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_USER_COORDS, "").trim();
    }
    public static void setUserCoords(Context context, String coords) {
        String trimmed = coords == null ? "" : coords.trim();
        SharedPreferences.Editor ed = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_USER_COORDS, trimmed);
        if (trimmed.isEmpty()) {
            ed.remove(KEY_USER_COORDS_UPDATED_MS);
        } else {
            ed.putLong(KEY_USER_COORDS_UPDATED_MS, System.currentTimeMillis());
        }
        ed.apply();
    }

    /** Epoch millis du dernier enregistrement de {@link #setUserCoords}, ou 0 si inconnu. */
    public static long getUserCoordsUpdatedMs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong(KEY_USER_COORDS_UPDATED_MS, 0L);
    }
}
