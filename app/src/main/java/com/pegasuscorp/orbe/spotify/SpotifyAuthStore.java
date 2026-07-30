package com.pegasuscorp.orbe.spotify;

import android.content.Context;

/**
 * Jetons OAuth Spotify (refresh + access).
 */
public final class SpotifyAuthStore {

    private static final String PREFS = "orbe_spotify_auth";
    private static final String KEY_ACCESS = "access_token";
    private static final String KEY_REFRESH = "refresh_token";
    private static final String KEY_EXPIRES_AT = "expires_at_ms";
    private static final String KEY_CODE_VERIFIER = "code_verifier";

    private SpotifyAuthStore() {}

    public static boolean isConnected(Context context) {
        return !getRefreshToken(context).isEmpty();
    }

    public static String getAccessToken(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_ACCESS, "").trim();
    }

    public static String getRefreshToken(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_REFRESH, "").trim();
    }

    public static long getExpiresAtMs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong(KEY_EXPIRES_AT, 0L);
    }

    public static void saveTokens(Context context, String access, String refresh, long expiresInSec) {
        long expiresAt = System.currentTimeMillis() + Math.max(30L, expiresInSec - 30L) * 1000L;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_ACCESS, access == null ? "" : access)
                .putString(KEY_REFRESH, refresh == null ? "" : refresh)
                .putLong(KEY_EXPIRES_AT, expiresAt)
                .remove(KEY_CODE_VERIFIER)
                .apply();
    }

    public static void saveAccessToken(Context context, String access, long expiresInSec) {
        long expiresAt = System.currentTimeMillis() + Math.max(30L, expiresInSec - 30L) * 1000L;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_ACCESS, access == null ? "" : access)
                .putLong(KEY_EXPIRES_AT, expiresAt)
                .apply();
    }

    public static void saveCodeVerifier(Context context, String verifier) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_CODE_VERIFIER, verifier == null ? "" : verifier)
                .apply();
    }

    public static String getCodeVerifier(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_CODE_VERIFIER, "").trim();
    }

    public static void clear(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
    }
}
