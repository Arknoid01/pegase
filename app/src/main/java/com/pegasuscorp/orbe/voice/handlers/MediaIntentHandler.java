package com.pegasuscorp.orbe.voice.handlers;

import android.content.Context;

import com.pegasuscorp.orbe.spotify.SpotifyQueryNormalizer;
import com.pegasuscorp.orbe.voice.VoiceIntentRouter.RoutedIntent;
import com.pegasuscorp.orbe.voice.VoiceIntentSupport;

import org.json.JSONObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MediaIntentHandler implements IntentHandler {

    @Override
    public RoutedIntent tryHandle(Context context, String text, String fold) {
        if (looksLikeNasa(fold)) {
            String json = VoiceIntentSupport.toolJson("nasa", new JSONObject());
            return VoiceIntentSupport.routed(context, text, json, "nasa", scoreNasa(fold));
        }

        if (looksLikeYouTube(fold)) {
            RoutedIntent yt = routeYouTube(context, text, fold);
            if (yt != null) return yt;
        }

        if (looksLikeSpotify(fold)) {
            RoutedIntent spotify = routeSpotify(context, text, fold);
            if (spotify != null) return spotify;
        }

        return null;
    }

    static boolean looksLikeNasa(String fold) {
        return fold.contains("nasa")
                || fold.contains("photo du jour")
                || fold.contains("image du jour")
                || fold.contains("apod")
                || (fold.contains("photo") && fold.contains("espace"))
                || (fold.contains("image") && fold.contains("astronom"));
    }

    static double scoreNasa(String fold) {
        if (fold.contains("nasa")) return 0.9;
        if (fold.contains("photo du jour")) return 0.88;
        return 0.75;
    }

    static boolean looksLikeSpotify(String fold) {
        String f = fold.replace("playslite", "playlist").replace("play liste", "playlist");
        return f.contains("spotify")
                || f.contains("playlist")
                || f.matches(".*meilleure?s? chansons?.*")
                || f.matches(".*meilleure?s? titres?.*")
                || f.contains("musique de")
                || f.contains("chanson de")
                || f.contains("mets du")
                || f.contains("mets de la")
                || f.contains("mets moi")
                || f.contains("joue ")
                || f.contains("joue-moi")
                || f.contains("lance ")
                || (f.contains("pause") && f.contains("musique"))
                || f.contains("met en pause")
                || f.contains("mets en pause")
                || f.contains("chanson suivante")
                || f.contains("piste suivante")
                || f.contains("qu est ce qui joue")
                || f.contains("quelle chanson")
                || f.contains("quelle musique");
    }

    static RoutedIntent routeSpotify(Context context, String text, String fold) {
        try {
            if (fold.contains("pause") || fold.contains("arrete") || fold.contains("stop")) {
                if (fold.contains("musique") || fold.contains("spotify")
                        || fold.contains("chanson") || fold.contains("lecture")
                        || fold.contains("ca") || fold.contains("la")) {
                    return VoiceIntentSupport.routed(context, text, VoiceIntentSupport.toolJson("spotify",
                            new JSONObject().put("action", "pause")), "spotify", 0.9);
                }
            }
            if (fold.contains("suivant") || fold.contains("chanson suivante")
                    || fold.contains("piste suivante") || fold.equals("next")) {
                return VoiceIntentSupport.routed(context, text, VoiceIntentSupport.toolJson("spotify",
                        new JSONObject().put("action", "next")), "spotify", 0.88);
            }
            if (fold.contains("precedent") || fold.contains("chanson precedente")
                    || fold.equals("previous") || fold.equals("prev")) {
                return VoiceIntentSupport.routed(context, text, VoiceIntentSupport.toolJson("spotify",
                        new JSONObject().put("action", "previous")), "spotify", 0.88);
            }
            if (fold.contains("qu est ce qui joue") || fold.contains("quelle chanson")
                    || fold.contains("que joue") || fold.contains("qu est ce qui passe")
                    || fold.contains("quelle musique")) {
                return VoiceIntentSupport.routed(context, text, VoiceIntentSupport.toolJson("spotify",
                        new JSONObject().put("action", "now_playing")), "spotify", 0.9);
            }

            SpotifyQueryNormalizer.PlaylistRequest playlist =
                    SpotifyQueryNormalizer.detectPlaylistRequest(text);
            if (playlist != null && playlist.isValid()) {
                JSONObject p = new JSONObject()
                        .put("action", "playlist")
                        .put("query", playlist.subject);
                return VoiceIntentSupport.routed(context, text, VoiceIntentSupport.toolJson("spotify", p), "spotify", 0.93);
            }

            String artist = SpotifyQueryNormalizer.extractArtistFromSpeech(text);
            if (artist != null && !artist.isEmpty()) {
                double conf = scoreSpotifyPlay(fold, artist);
                JSONObject p = new JSONObject().put("action", "play").put("query", artist);
                return VoiceIntentSupport.routed(context, text, VoiceIntentSupport.toolJson("spotify", p), "spotify", conf);
            }

            if (fold.contains("lance spotify") || fold.contains("ouvre spotify")
                    || fold.equals("spotify")) {
                return VoiceIntentSupport.routed(context, text, VoiceIntentSupport.toolJson("spotify",
                        new JSONObject().put("action", "play")), "spotify", 0.82);
            }
        } catch (Exception ignored) {}
        return null;
    }

    static double scoreSpotifyPlay(String fold, String artist) {
        double score = 0.72;
        if (fold.contains("spotify")) score += 0.1;
        if (fold.contains("mets") || fold.contains("joue") || fold.contains("lance")) score += 0.08;
        if (artist.split("\\s+").length >= 2) score += 0.05;
        return Math.min(0.96, score);
    }

    static boolean looksLikeYouTube(String fold) {
        return fold.contains("youtube") || fold.contains("youtu be")
                || fold.contains("video sur") || fold.contains("une video");
    }

    static RoutedIntent routeYouTube(Context context, String text, String fold) {
        try {
            String query = extractYouTubeQuery(text, fold);
            JSONObject p = new JSONObject().put("action", "search");
            if (query != null && !query.isEmpty()) {
                p.put("query", query);
                return VoiceIntentSupport.routed(context, text, VoiceIntentSupport.toolJson("youtube", p), "youtube", 0.86);
            }
            return VoiceIntentSupport.routed(context, text,
                    VoiceIntentSupport.toolJson("youtube", new JSONObject().put("action", "open")),
                    "youtube", 0.8);
        } catch (Exception e) {
            return null;
        }
    }

    static String extractYouTubeQuery(String text, String fold) {
        Matcher m = Pattern.compile(
                "(?i)(?:cherche|lance|joue|mets|ouvre)(?:\\s+(?:moi|une))?\\s+(.+?)\\s+sur\\s+youtube")
                .matcher(text);
        if (m.find()) return m.group(1).trim();
        m = Pattern.compile("(?i)youtube\\s+(.+)$").matcher(text);
        if (m.find()) return m.group(1).trim();
        if (fold.contains("video")) {
            m = Pattern.compile("(?i)(?:video|vidéo)\\s+(?:de|sur|d')?\\s*(.+)$").matcher(text);
            if (m.find()) return m.group(1).trim();
        }
        return null;
    }
}
