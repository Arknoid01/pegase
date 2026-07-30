package com.pegasuscorp.orbe.spotify;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import com.pegasuscorp.orbe.chat.ApiKeyStore;
import com.pegasuscorp.orbe.tools.HttpJson;

import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Lance et finalise l'OAuth Spotify (Authorization Code + PKCE). */
public final class SpotifyAuthHelper {

    public static final String REDIRECT_URI = "com.pegasuscorp.orbe://spotify-callback";
    private static final String AUTH_URL = "https://accounts.spotify.com/authorize";
    private static final String TOKEN_URL = "https://accounts.spotify.com/api/token";
    private static final String SCOPES =
            "user-read-playback-state user-modify-playback-state user-read-currently-playing";

    private SpotifyAuthHelper() {}

    public static boolean canUseApi(Context context) {
        return ApiKeyStore.hasSpotifyClientId(context) && SpotifyAuthStore.isConnected(context);
    }

    public static void launchAuthorization(Context context) throws Exception {
        String clientId = ApiKeyStore.getSpotifyClientId(context);
        if (clientId.isEmpty()) {
            throw new IllegalStateException("Client ID Spotify manquant");
        }
        String verifier = SpotifyPkce.generateVerifier();
        String challenge = SpotifyPkce.challenge(verifier);
        SpotifyAuthStore.saveCodeVerifier(context, verifier);

        String url = AUTH_URL
                + "?client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8.name())
                + "&response_type=code"
                + "&redirect_uri=" + URLEncoder.encode(REDIRECT_URI, StandardCharsets.UTF_8.name())
                + "&scope=" + URLEncoder.encode(SCOPES, StandardCharsets.UTF_8.name())
                + "&code_challenge_method=S256"
                + "&code_challenge=" + URLEncoder.encode(challenge, StandardCharsets.UTF_8.name())
                + "&access_type=offline";

        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    public static void exchangeAuthorizationCode(Context context, String code) throws Exception {
        String clientId = ApiKeyStore.getSpotifyClientId(context);
        String verifier = SpotifyAuthStore.getCodeVerifier(context);
        if (clientId.isEmpty() || verifier.isEmpty()) {
            throw new IllegalStateException("Session OAuth incomplète — réessaie la connexion.");
        }

        Map<String, String> form = new HashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", REDIRECT_URI);
        form.put("client_id", clientId);
        form.put("code_verifier", verifier);

        JSONObject res = HttpJson.postForm(TOKEN_URL, form, null);
        String access = res.optString("access_token", "");
        String refresh = res.optString("refresh_token", "");
        long expiresIn = res.optLong("expires_in", 3600);
        if (access.isEmpty()) {
            throw new IllegalStateException("Réponse Spotify invalide");
        }
        if (refresh.isEmpty()) {
            refresh = SpotifyAuthStore.getRefreshToken(context);
        }
        if (refresh.isEmpty()) {
            throw new IllegalStateException("Refresh token manquant — réessaie la connexion.");
        }
        SpotifyAuthStore.saveTokens(context, access, refresh, expiresIn);
    }
}
