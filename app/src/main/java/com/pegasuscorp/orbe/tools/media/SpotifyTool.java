package com.pegasuscorp.orbe.tools.media;

import com.pegasuscorp.orbe.tools.ToolTag;

import com.pegasuscorp.orbe.tools.ToolResult;

import com.pegasuscorp.orbe.tools.Tool;
import com.pegasuscorp.orbe.tools.ToolCallback;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.net.Uri;
import android.view.KeyEvent;

import com.pegasuscorp.orbe.chat.ApiKeyStore;
import com.pegasuscorp.orbe.spotify.SpotifyApiClient;
import com.pegasuscorp.orbe.spotify.SpotifyQueryNormalizer;
import com.pegasuscorp.orbe.spotify.SpotifyAuthHelper;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Contrôle Spotify : API Web (Premium) si connecté, sinon raccourcis système.
 */
public final class SpotifyTool implements Tool {

    private final ExecutorService io = Executors.newSingleThreadExecutor();

    @Override public String id() { return "spotify"; }

    @Override public ToolTag tag() { return ToolTag.SPOTIFY; }

    @Override
    public String description() {
        return "spotify(action:\"play\"|\"playlist\"|\"pause\"|\"next\"|\"previous\"|\"now_playing\", query?:str)"
                + " — Lance une piste, une playlist (meilleurs titres d'un artiste), contrôle la lecture "
                + "(compte Spotify Premium connecté dans les réglages).";
    }

    @Override
    public void execute(Context ctx, JSONObject params, ToolCallback cb) {
        String action = params.optString("action", "play").toLowerCase();
        String query = params.optString("query", "").trim();

        if (SpotifyAuthHelper.canUseApi(ctx)) {
            io.execute(() -> executeViaApi(ctx, action, query, cb));
            return;
        }

        if (ApiKeyStore.hasSpotifyClientId(ctx)) {
            cb.onError("Connecte ton compte Spotify dans les réglages API pour lancer des titres précis.");
            return;
        }
        executeLegacy(ctx, action, query, cb);
    }

    private void executeViaApi(Context ctx, String action, String query, ToolCallback cb) {
        try {
            String normalizedQuery = SpotifyQueryNormalizer.normalize(ctx, query);
            SpotifyQueryNormalizer.PlaylistRequest playlistRequest =
                    SpotifyQueryNormalizer.detectPlaylistRequest(query);
            String reply;
            switch (action) {
                case "pause":
                case "stop":
                    reply = SpotifyApiClient.pause(ctx);
                    break;
                case "next":
                    reply = SpotifyApiClient.next(ctx);
                    break;
                case "previous":
                case "prev":
                    reply = SpotifyApiClient.previous(ctx);
                    break;
                case "now_playing":
                case "status":
                case "current":
                    reply = SpotifyApiClient.nowPlaying(ctx);
                    break;
                case "playlist":
                    if (normalizedQuery.isEmpty()) {
                        cb.onError("Précise quelle playlist ou quel artiste lancer.");
                        return;
                    }
                    reply = SpotifyApiClient.playPlaylistQuery(ctx, normalizedQuery);
                    break;
                case "search":
                    if (normalizedQuery.isEmpty()) {
                        cb.onError("Précise quoi chercher sur Spotify.");
                        return;
                    }
                    if (playlistRequest != null && playlistRequest.isValid()) {
                        reply = SpotifyApiClient.playPlaylistQuery(ctx,
                                SpotifyQueryNormalizer.normalize(ctx, playlistRequest.subject));
                    } else {
                        reply = SpotifyApiClient.playQuery(ctx, normalizedQuery);
                    }
                    break;
                case "play":
                default:
                    if (playlistRequest != null && playlistRequest.isValid()) {
                        reply = SpotifyApiClient.playPlaylistQuery(ctx,
                                SpotifyQueryNormalizer.normalize(ctx, playlistRequest.subject));
                    } else if (!normalizedQuery.isEmpty()) {
                        reply = SpotifyApiClient.playQuery(ctx, normalizedQuery);
                    } else {
                        reply = SpotifyApiClient.resume(ctx);
                    }
                    break;
            }
            cb.onSuccess(ToolResult.text(reply));
        } catch (Exception e) {
            cb.onError(friendlySpotifyError(e));
        }
    }

    private static String friendlySpotifyError(Exception e) {
        String msg = e.getMessage() != null ? e.getMessage() : e.toString();
        if (msg.contains("HTTP 404") || msg.contains("NO_ACTIVE_DEVICE")) {
            return "Ouvre Spotify sur ton téléphone, puis réessaie.";
        }
        if (msg.contains("HTTP 403")) {
            return "Spotify Premium requis pour contrôler la lecture à distance.";
        }
        if (msg.contains("HTTP 405")) {
            return "Erreur Spotify (méthode API) — mets à jour Orbe et réessaie.";
        }
        return "Spotify : " + msg;
    }

    private void executeLegacy(Context ctx, String action, String query, ToolCallback cb) {
        String normalizedQuery = SpotifyQueryNormalizer.normalize(ctx, query);
        if ((action.equals("play") || action.equals("search") || action.equals("playlist"))
                && !normalizedQuery.isEmpty()) {
            SpotifyQueryNormalizer.PlaylistRequest playlistRequest =
                    SpotifyQueryNormalizer.detectPlaylistRequest(query);
            if (action.equals("playlist")
                    || (playlistRequest != null && playlistRequest.isValid())) {
                String subject = playlistRequest != null && playlistRequest.isValid()
                        ? SpotifyQueryNormalizer.normalize(ctx, playlistRequest.subject)
                        : normalizedQuery;
                openSpotifySearch(ctx, subject + " playlist", cb);
                return;
            }
            openSpotifySearch(ctx, normalizedQuery, cb);
            return;
        }

        int keyCode;
        String reply;
        switch (action) {
            case "pause":
            case "stop":
                keyCode = KeyEvent.KEYCODE_MEDIA_PAUSE;
                reply = "Musique en pause.";
                break;
            case "next":
                keyCode = KeyEvent.KEYCODE_MEDIA_NEXT;
                reply = "Chanson suivante.";
                break;
            case "previous":
            case "prev":
                keyCode = KeyEvent.KEYCODE_MEDIA_PREVIOUS;
                reply = "Chanson précédente.";
                break;
            default:
                keyCode = KeyEvent.KEYCODE_MEDIA_PLAY;
                reply = "C'est parti.";
        }

        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
        cb.onSuccess(ToolResult.text(reply));
    }

    private void openSpotifySearch(Context ctx, String query, ToolCallback cb) {
        Uri uri = Uri.parse("spotify:search:" + Uri.encode(query));
        Intent intent = new Intent(Intent.ACTION_VIEW, uri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (intent.resolveActivity(ctx.getPackageManager()) != null) {
            ctx.startActivity(intent);
            cb.onSuccessAndExit(ToolResult.text("Je cherche " + query + " sur Spotify."));
        } else {
            Uri webUri = Uri.parse("https://open.spotify.com/search/" + Uri.encode(query));
            ctx.startActivity(new Intent(Intent.ACTION_VIEW, webUri)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            cb.onSuccessAndExit(ToolResult.text("Spotify n'est pas installé, j'ouvre la version web."));
        }
    }
}
