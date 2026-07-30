package com.pegasuscorp.orbe.spotify;

import android.content.Context;

import com.pegasuscorp.orbe.chat.ApiKeyStore;
import com.pegasuscorp.orbe.tools.HttpJson;
import com.pegasuscorp.orbe.voice.SpeechInputNormalizer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Client Spotify Web API — lecture Premium sur l'appareil actif. */
public final class SpotifyApiClient {

    private static final String API_BASE = "https://api.spotify.com/v1";
    private static final String TOKEN_URL = "https://accounts.spotify.com/api/token";

    private SpotifyApiClient() {}

    public static String playPlaylistQuery(Context ctx, String subject) throws Exception {
        String display = subject == null ? "" : subject.trim();
        List<String> variants = SpotifyQueryNormalizer.playlistSearchVariants(ctx, display);
        JSONObject playlist = resolvePlaylist(ctx, variants);
        if (playlist != null) {
            String uri = playlist.optString("uri", "");
            String name = playlist.optString("name", display);
            if (!uri.isEmpty()) {
                playContextUri(ctx, uri);
                return "Je lance la playlist « " + name + " ».";
            }
        }
        return playArtistTopTracksQueue(ctx, display);
    }

    public static String playQuery(Context ctx, String query) throws Exception {
        String displayQuery = query == null ? "" : query.trim();
        JSONObject track = resolvePlayableTrack(ctx, displayQuery);
        if (track == null) {
            String tried = SpotifyQueryNormalizer.normalize(ctx, displayQuery);
            String shown = tried.isEmpty() ? displayQuery : tried;
            return "Je n'ai rien trouvé pour « " + shown + " » sur Spotify.";
        }
        String uri = track.optString("uri", "");
        String title = track.optString("name", displayQuery);
        String artists = joinArtists(track.optJSONArray("artists"));
        if (uri.isEmpty()) {
            return "Je n'ai pas pu lancer cette piste.";
        }
        playUri(ctx, uri);
        return "Je lance " + title + (artists.isEmpty() ? "" : " par " + artists) + ".";
    }

    public static String pause(Context ctx) throws Exception {
        putEmpty(ctx, "/me/player/pause");
        return "Musique en pause.";
    }

    public static String next(Context ctx) throws Exception {
        post(ctx, "/me/player/next");
        return "Chanson suivante.";
    }

    public static String previous(Context ctx) throws Exception {
        post(ctx, "/me/player/previous");
        return "Chanson précédente.";
    }

    public static String resume(Context ctx) throws Exception {
        try {
            putEmpty(ctx, "/me/player/play");
        } catch (IllegalStateException e) {
            if (e.getMessage() != null && e.getMessage().contains("404")) {
                transferToPhoneAndResume(ctx);
                return "C'est parti.";
            }
            throw e;
        }
        return "C'est parti.";
    }

    public static String nowPlaying(Context ctx) throws Exception {
        JSONObject res = get(ctx, "/me/player/currently-playing");
        if (res.length() == 0) {
            return "Rien ne joue sur Spotify pour l'instant.";
        }
        JSONObject item = res.optJSONObject("item");
        if (item == null) {
            return "Rien ne joue sur Spotify pour l'instant.";
        }
        String title = item.optString("name", "Titre inconnu");
        String artists = joinArtists(item.optJSONArray("artists"));
        boolean playing = res.optBoolean("is_playing", false);
        if (playing) {
            return "Ça joue " + title + (artists.isEmpty() ? "" : " par " + artists) + ".";
        }
        return "En pause : " + title + (artists.isEmpty() ? "" : " par " + artists) + ".";
    }

    private static JSONObject resolvePlayableTrack(Context ctx, String query) throws Exception {
        List<String> variants = SpotifyQueryNormalizer.searchVariants(ctx, query);
        for (String variant : variants) {
            if (variant == null || variant.isEmpty()) continue;
            JSONObject track = searchBestMatch(ctx, variant);
            if (track != null) return track;
        }
        return null;
    }

    private static JSONObject searchBestMatch(Context ctx, String query) throws Exception {
        JSONObject artistTop = searchArtistTopTrack(ctx, query);
        if (artistTop != null) return artistTop;

        JSONObject track = searchTrack(ctx, query);
        if (track != null) return track;

        return searchTrack(ctx, "artist:" + query);
    }

    private static JSONObject searchArtistTopTrack(Context ctx, String query) throws Exception {
        JSONObject bestArtist = findBestArtist(ctx, query);
        if (bestArtist == null) return null;

        String artistId = bestArtist.optString("id", "");
        if (artistId.isEmpty()) return null;
        JSONObject top = get(ctx, "/artists/" + artistId + "/top-tracks?market=FR");
        JSONArray topItems = top.optJSONArray("tracks");
        if (topItems == null || topItems.length() == 0) return null;
        return topItems.getJSONObject(0);
    }

    private static JSONObject findBestArtist(Context ctx, String query) throws Exception {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name());
        JSONObject search = get(ctx, "/search?q=" + encoded + "&type=artist&limit=5");
        JSONObject artists = search.optJSONObject("artists");
        if (artists == null) return null;
        JSONArray items = artists.optJSONArray("items");
        if (items == null || items.length() == 0) return null;
        return pickBestArtist(items, query);
    }

    private static JSONObject resolvePlaylist(Context ctx, List<String> variants) throws Exception {
        for (String variant : variants) {
            if (variant == null || variant.isEmpty()) continue;
            JSONObject playlist = searchBestPlaylist(ctx, variant);
            if (playlist != null) return playlist;
        }
        return null;
    }

    private static JSONObject searchBestPlaylist(Context ctx, String query) throws Exception {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name());
        JSONObject search = get(ctx, "/search?q=" + encoded + "&type=playlist&limit=10");
        JSONObject playlists = search.optJSONObject("playlists");
        if (playlists == null) return null;
        JSONArray items = playlists.optJSONArray("items");
        if (items == null || items.length() == 0) return null;
        return pickBestPlaylist(items, query);
    }

    private static JSONObject pickBestPlaylist(JSONArray items, String query) {
        String queryFold = SpeechInputNormalizer.fold(query);
        JSONObject best = null;
        int bestScore = -1;
        for (int i = 0; i < items.length(); i++) {
            JSONObject playlist = items.optJSONObject(i);
            if (playlist == null) continue;
            int score = playlistMatchScore(queryFold, playlist);
            if (score > bestScore) {
                bestScore = score;
                best = playlist;
            }
        }
        if (best == null || bestScore < 20) return null;
        return best;
    }

    private static int playlistMatchScore(String queryFold, JSONObject playlist) {
        String name = playlist.optString("name", "");
        String nameFold = SpeechInputNormalizer.fold(name);
        int score = 0;
        if (nameFold.contains(queryFold) || queryFold.contains(nameFold)) score += 45;
        if (nameFold.contains("this is")) score += 25;
        if (nameFold.contains("best") || nameFold.contains("top") || nameFold.contains("hits")) score += 15;
        if (nameFold.contains("meilleur") || nameFold.contains("essentiel")
                || nameFold.contains("essentials")) score += 15;

        JSONObject owner = playlist.optJSONObject("owner");
        if (owner != null) {
            String ownerId = owner.optString("id", "");
            if ("spotify".equals(ownerId)) score += 20;
            String displayName = SpeechInputNormalizer.fold(owner.optString("display_name", ""));
            if (displayName.contains("spotify")) score += 10;
        }
        return score;
    }

    private static String playArtistTopTracksQueue(Context ctx, String subject) throws Exception {
        List<String> variants = SpotifyQueryNormalizer.searchVariants(ctx, subject);
        for (String variant : variants) {
            if (variant == null || variant.isEmpty()) continue;
            JSONObject artist = findBestArtist(ctx, variant);
            if (artist == null) continue;

            String artistId = artist.optString("id", "");
            String artistName = artist.optString("name", variant);
            if (artistId.isEmpty()) continue;

            JSONObject top = get(ctx, "/artists/" + artistId + "/top-tracks?market=FR");
            JSONArray tracks = top.optJSONArray("tracks");
            if (tracks == null || tracks.length() == 0) continue;

            JSONArray uris = new JSONArray();
            int limit = Math.min(tracks.length(), 15);
            for (int i = 0; i < limit; i++) {
                JSONObject track = tracks.optJSONObject(i);
                if (track == null) continue;
                String uri = track.optString("uri", "");
                if (!uri.isEmpty()) uris.put(uri);
            }
            if (uris.length() == 0) continue;

            playUris(ctx, uris);
            return "Je lance les meilleurs titres de " + artistName + ".";
        }

        String shown = SpotifyQueryNormalizer.normalize(ctx, subject);
        return "Je n'ai pas trouvé de playlist pour « " + shown + " » sur Spotify.";
    }

    private static JSONObject searchTrack(Context ctx, String query) throws Exception {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name());
        JSONObject search = get(ctx, "/search?q=" + encoded + "&type=track&limit=5");
        JSONObject tracks = search.optJSONObject("tracks");
        if (tracks == null) return null;
        JSONArray items = tracks.optJSONArray("items");
        if (items == null || items.length() == 0) return null;
        return items.getJSONObject(0);
    }

    private static JSONObject pickBestArtist(JSONArray items, String query) {
        String queryFold = SpeechInputNormalizer.fold(query);
        JSONObject best = null;
        int bestScore = -1;
        for (int i = 0; i < items.length(); i++) {
            JSONObject artist = items.optJSONObject(i);
            if (artist == null) continue;
            String name = artist.optString("name", "");
            int score = artistMatchScore(queryFold, SpeechInputNormalizer.fold(name));
            if (score > bestScore) {
                bestScore = score;
                best = artist;
            }
        }
        if (best == null || bestScore < 40) return items.optJSONObject(0);
        return best;
    }

    private static int artistMatchScore(String queryFold, String nameFold) {
        if (queryFold.isEmpty() || nameFold.isEmpty()) return 0;
        if (queryFold.equals(nameFold)) return 100;
        if (nameFold.contains(queryFold) || queryFold.contains(nameFold)) return 85;
        String[] qWords = queryFold.split("\\s+");
        int hits = 0;
        for (String w : qWords) {
            if (w.length() >= 3 && nameFold.contains(w)) hits++;
        }
        return hits * 25;
    }

    private static void playUri(Context ctx, String uri) throws Exception {
        playUris(ctx, new JSONArray().put(uri));
    }

    private static void playUris(Context ctx, JSONArray uris) throws Exception {
        try {
            JSONObject body = new JSONObject().put("uris", uris);
            put(ctx, "/me/player/play", body);
        } catch (IllegalStateException e) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (msg.contains("404") || msg.contains("NO_ACTIVE_DEVICE")) {
                transferToPhoneAndPlayUris(ctx, uris);
                return;
            }
            throw e;
        }
    }

    private static void playContextUri(Context ctx, String contextUri) throws Exception {
        try {
            JSONObject body = new JSONObject().put("context_uri", contextUri);
            put(ctx, "/me/player/play", body);
        } catch (IllegalStateException e) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (msg.contains("404") || msg.contains("NO_ACTIVE_DEVICE")) {
                transferToPhoneAndPlayContext(ctx, contextUri);
                return;
            }
            throw e;
        }
    }

    private static void transferToPhoneAndPlayUris(Context ctx, JSONArray uris) throws Exception {
        String deviceId = pickActivePhoneDevice(ctx);
        JSONObject transfer = new JSONObject()
                .put("device_ids", new JSONArray().put(deviceId))
                .put("play", false);
        put(ctx, "/me/player", transfer);
        JSONObject body = new JSONObject().put("uris", uris);
        put(ctx, "/me/player/play?device_id=" + URLEncoder.encode(deviceId, "UTF-8"), body);
    }

    private static void transferToPhoneAndPlayContext(Context ctx, String contextUri) throws Exception {
        String deviceId = pickActivePhoneDevice(ctx);
        JSONObject transfer = new JSONObject()
                .put("device_ids", new JSONArray().put(deviceId))
                .put("play", false);
        put(ctx, "/me/player", transfer);
        JSONObject body = new JSONObject().put("context_uri", contextUri);
        put(ctx, "/me/player/play?device_id=" + URLEncoder.encode(deviceId, "UTF-8"), body);
    }

    private static void transferToPhoneAndResume(Context ctx) throws Exception {
        String deviceId = pickActivePhoneDevice(ctx);
        JSONObject transfer = new JSONObject()
                .put("device_ids", new JSONArray().put(deviceId))
                .put("play", true);
        put(ctx, "/me/player", transfer);
    }

    private static String pickActivePhoneDevice(Context ctx) throws Exception {
        JSONObject devicesRes = get(ctx, "/me/player/devices");
        JSONArray devices = devicesRes.optJSONArray("devices");
        if (devices == null || devices.length() == 0) {
            throw new IllegalStateException(
                    "Ouvre Spotify sur ton téléphone, puis réessaie.");
        }
        return pickPhoneDevice(devices);
    }

    private static String pickPhoneDevice(JSONArray devices) throws Exception {
        String fallback = null;
        for (int i = 0; i < devices.length(); i++) {
            JSONObject d = devices.getJSONObject(i);
            String id = d.optString("id", "");
            if (id.isEmpty()) continue;
            String type = d.optString("type", "").toLowerCase();
            if ("smartphone".equals(type)) return id;
            if (fallback == null) fallback = id;
        }
        if (fallback == null) throw new IllegalStateException("Aucun appareil Spotify disponible.");
        return fallback;
    }

    private static JSONObject get(Context ctx, String path) throws Exception {
        Map<String, String> headers = authHeaders(ctx);
        return HttpJson.get(API_BASE + path, headers);
    }

    private static void post(Context ctx, String path) throws Exception {
        HttpJson.postEmpty(API_BASE + path, authHeaders(ctx));
    }

    private static void putEmpty(Context ctx, String path) throws Exception {
        HttpJson.putJson(API_BASE + path, authHeaders(ctx), null);
    }

    private static void put(Context ctx, String path, JSONObject body) throws Exception {
        HttpJson.putJson(API_BASE + path, authHeaders(ctx), body);
    }

    private static Map<String, String> authHeaders(Context ctx) throws Exception {
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + ensureAccessToken(ctx));
        return headers;
    }

    private static String ensureAccessToken(Context ctx) throws Exception {
        String access = SpotifyAuthStore.getAccessToken(ctx);
        if (!access.isEmpty() && System.currentTimeMillis() < SpotifyAuthStore.getExpiresAtMs(ctx)) {
            return access;
        }
        refreshAccessToken(ctx);
        return SpotifyAuthStore.getAccessToken(ctx);
    }

    private static void refreshAccessToken(Context ctx) throws Exception {
        String refresh = SpotifyAuthStore.getRefreshToken(ctx);
        String clientId = ApiKeyStore.getSpotifyClientId(ctx);
        if (refresh.isEmpty() || clientId.isEmpty()) {
            throw new IllegalStateException("Compte Spotify déconnecté — reconnecte-toi dans les réglages.");
        }
        Map<String, String> form = new HashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", refresh);
        form.put("client_id", clientId);
        JSONObject res = HttpJson.postForm(TOKEN_URL, form, null);
        String access = res.optString("access_token", "");
        long expiresIn = res.optLong("expires_in", 3600);
        if (access.isEmpty()) {
            throw new IllegalStateException("Impossible de rafraîchir la session Spotify.");
        }
        String newRefresh = res.optString("refresh_token", "");
        if (!newRefresh.isEmpty()) {
            SpotifyAuthStore.saveTokens(ctx, access, newRefresh, expiresIn);
        } else {
            SpotifyAuthStore.saveAccessToken(ctx, access, expiresIn);
        }
    }

    private static String joinArtists(JSONArray artists) {
        if (artists == null || artists.length() == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < artists.length(); i++) {
            JSONObject artist = artists.optJSONObject(i);
            if (artist == null) continue;
            String name = artist.optString("name", "").trim();
            if (name.isEmpty()) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(name);
        }
        return sb.toString();
    }
}
