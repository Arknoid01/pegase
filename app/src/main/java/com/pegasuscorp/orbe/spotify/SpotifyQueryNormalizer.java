package com.pegasuscorp.orbe.spotify;

import android.content.Context;

import com.pegasuscorp.orbe.voice.SpeechInputNormalizer;
import com.pegasuscorp.orbe.voice.VoiceCorrectionStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Nettoie les requêtes Spotify issues du STT ou du LLM : retire les tournures orales
 * françaises et corrige les noms d'artistes mal entendus.
 */
public final class SpotifyQueryNormalizer {

    private static final Pattern TRAILING_SPOTIFY = Pattern.compile(
            "(?i)\\s+(?:sur|avec|dans|via)\\s+spotify\\s*$");
    private static final Pattern TRAILING_FILLER = Pattern.compile(
            "(?i)\\s+(?:s il te plait|stp|merci|la|le|les)\\s*$");

    private static final String[] COMMAND_PREFIXES = {
            "mets moi de la musique de ",
            "mets moi du ",
            "mets moi de la ",
            "mets moi des ",
            "mets moi ",
            "mets-moi de la musique de ",
            "mets-moi du ",
            "mets-moi de la ",
            "mets-moi des ",
            "mets-moi ",
            "mets de la musique de ",
            "mets du ",
            "mets de la ",
            "mets des ",
            "mets la musique de ",
            "mets un peu de ",
            "mets ",
            "met moi de la musique de ",
            "met moi du ",
            "met moi ",
            "met de la musique de ",
            "met du ",
            "met ",
            "joue moi de la musique de ",
            "joue moi du ",
            "joue moi ",
            "joue-moi ",
            "joue de la musique de ",
            "joue du ",
            "joue la musique de ",
            "joue ",
            "lance moi ",
            "lance-moi ",
            "lance la musique de ",
            "lance ",
            "passe moi ",
            "passe-moi ",
            "passe ",
            "écoute ",
            "ecoute ",
            "fais moi écouter ",
            "fais moi ecouter ",
            "fais écouter ",
            "fais ecouter ",
            "la musique de ",
            "l'artiste ",
            "l artiste ",
            "le groupe ",
            "quelque chose de ",
            "un peu de ",
    };

    private static final Map<String, String> ARTIST_ALIASES = new LinkedHashMap<>();

    static {
        alias("daft punck", "Daft Punk");
        alias("dafte punk", "Daft Punk");
        alias("draft punk", "Daft Punk");
        alias("stromé", "Stromae");
        alias("stromait", "Stromae");
        alias("stromay", "Stromae");
        alias("stromae", "Stromae");
        alias("cold play", "Coldplay");
        alias("coldplay", "Coldplay");
        alias("radio head", "Radiohead");
        alias("pink floyd", "Pink Floyd");
        alias("linkin park", "Linkin Park");
        alias("system of a down", "System Of A Down");
        alias("red hot chili peppers", "Red Hot Chili Peppers");
        alias("red hot", "Red Hot Chili Peppers");
        alias("the week end", "The Weeknd");
        alias("week end", "The Weeknd");
        alias("weeknd", "The Weeknd");
        alias("kendrick lamar", "Kendrick Lamar");
        alias("kendrick", "Kendrick Lamar");
        alias("booba", "Booba");
        alias("disiz", "Disiz");
        alias("orelsan", "Orelsan");
        alias("angele", "Angèle");
        alias("angèle", "Angèle");
        alias("gims", "Gims");
        alias("jul", "Jul");
        alias("ninho", "Ninho");
        alias("damso", "Damso");
        alias("vald", "Vald");
        alias("pnl", "PNL");
        alias("justice", "Justice");
        alias("air", "AIR");
        alias("m83", "M83");
        alias("mylene farmer", "Mylène Farmer");
        alias("mylène farmer", "Mylène Farmer");
        alias("indochine", "Indochine");
        alias("téléphone", "Téléphone");
        alias("telephone", "Téléphone");
        alias("nouvelle vague", "Nouvelle Vague");
        alias("phoenix", "Phoenix");
        alias("sebastien tellier", "Sébastien Tellier");
        alias("sébastien tellier", "Sébastien Tellier");
        alias("kavinsky", "Kavinsky");
        alias("carpenter brut", "Carpenter Brut");
        alias("metallica", "Metallica");
        alias("metaillica", "Metallica");
        alias("ac dc", "AC/DC");
        alias("acdc", "AC/DC");
        alias("led zeppelin", "Led Zeppelin");
        alias("queen", "Queen");
        alias("nirvana", "Nirvana");
        alias("billie eilish", "Billie Eilish");
        alias("billy eilish", "Billie Eilish");
        alias("taylor swift", "Taylor Swift");
        alias("ed sheeran", "Ed Sheeran");
        alias("arctic monkeys", "Arctic Monkeys");
        alias("depeche mode", "Depeche Mode");
        alias("depêche mode", "Depeche Mode");
        alias("massive attack", "Massive Attack");
        alias("portishead", "Portishead");
        alias("fatboy slim", "Fatboy Slim");
        alias("chemical brothers", "The Chemical Brothers");
        alias("prodigy", "The Prodigy");
        alias("the prodigy", "The Prodigy");
    }

    private SpotifyQueryNormalizer() {}

    public static String normalize(Context ctx, String raw) {
        if (raw == null) return "";
        String out = raw.trim();
        if (out.isEmpty()) return "";

        out = TRAILING_SPOTIFY.matcher(out).replaceAll("").trim();
        out = TRAILING_FILLER.matcher(out).replaceAll("").trim();
        out = stripCommandPrefixes(out);

        if (ctx != null) {
            out = VoiceCorrectionStore.getInstance(ctx).apply(out);
        }
        out = applyArtistAliases(out);
        return out.replaceAll("\\s+", " ").trim();
    }

    /** Variantes de recherche à essayer sur l'API Spotify (ordre décroissant de pertinence). */
    public static List<String> searchVariants(Context ctx, String raw) {
        List<String> variants = new ArrayList<>();
        String primary = normalize(ctx, raw);
        if (!primary.isEmpty()) variants.add(primary);

        String stripped = stripCommandPrefixes(raw == null ? "" : raw.trim());
        if (!stripped.isEmpty() && !variants.contains(stripped)) {
            variants.add(applyArtistAliases(stripped));
        }

        String foldedPrimary = SpeechInputNormalizer.fold(primary);
        for (Map.Entry<String, String> alias : ARTIST_ALIASES.entrySet()) {
            if (foldedPrimary.contains(alias.getKey()) && !variants.contains(alias.getValue())) {
                variants.add(alias.getValue());
            }
        }
        return variants;
    }

    /** Demande de playlist (ex. « playlist des meilleures chansons d'Orelsan »). */
    public static final class PlaylistRequest {
        public final String subject;

        public PlaylistRequest(String subject) {
            this.subject = subject == null ? "" : subject.trim();
        }

        public boolean isValid() {
            return !subject.isEmpty();
        }
    }

    /**
     * Détecte une intention playlist : mot « playlist », « meilleures chansons », « top titres », etc.
     */
    public static PlaylistRequest detectPlaylistRequest(String transcript) {
        if (transcript == null) return null;
        String text = transcript.trim();
        if (text.isEmpty()) return null;

        String fold = foldPlaylistSpeech(text);
        if (!looksLikePlaylistIntent(fold)) return null;

        String subject = extractPlaylistSubject(text);
        if (subject.isEmpty()) return null;
        subject = normalize(null, subject);
        if (subject.isEmpty()) return null;
        return new PlaylistRequest(subject);
    }

    /** Variantes de recherche playlist sur l'API Spotify. */
    public static List<String> playlistSearchVariants(Context ctx, String subject) {
        List<String> variants = new ArrayList<>();
        String artist = normalize(ctx, subject);
        if (artist.isEmpty()) return variants;

        variants.add("This Is " + artist);
        variants.add(artist + " Best Of");
        variants.add(artist + " greatest hits");
        variants.add(artist + " top hits");
        variants.add(artist + " meilleures chansons");
        variants.add(artist + " essentials");
        variants.add(artist + " hits");
        if (!variants.contains(artist)) variants.add(artist);
        return variants;
    }

    /** Extrait le nom d'artiste / titre depuis une phrase vocale complète. */
    public static String extractArtistFromSpeech(String transcript) {
        if (transcript == null) return "";
        String text = transcript.trim();
        if (text.isEmpty()) return "";

        Matcher musicOf = Pattern.compile(
                "(?i)(?:musique|chanson|titres?|son)\\s+(?:de|du|des|d')\\s+(.+)$")
                .matcher(text);
        if (musicOf.find()) {
            return normalize(null, musicOf.group(1));
        }

        Matcher play = Pattern.compile(
                "(?i)(?:mets|met|joue|lance|passe|ecoute|écoute|fais)"
                        + "(?:\\s+(?:moi|nous))?"
                        + "(?:\\s+(?:du|de la|des|un peu de|quelque chose de|la musique de|de la musique de))?"
                        + "\\s+(.+)$")
                .matcher(text);
        if (play.find()) {
            return normalize(null, play.group(1));
        }

        if (SpeechInputNormalizer.fold(text).contains("spotify")) {
            String without = text.replaceAll("(?i)spotify", "").trim();
            without = without.replaceAll("(?i)^(sur|avec|dans|via|ouvre|lance)\\s+", "").trim();
            if (!without.isEmpty()) {
                return normalize(null, without);
            }
        }
        return "";
    }

    private static String foldPlaylistSpeech(String text) {
        return SpeechInputNormalizer.fold(text)
                .replace("playslite", "playlist")
                .replace("play liste", "playlist");
    }

    private static boolean looksLikePlaylistIntent(String fold) {
        if (fold.contains("playlist")) return true;
        if (fold.matches(".*meilleure?s? chansons?.*")) return true;
        if (fold.matches(".*meilleure?s? titres?.*")) return true;
        if (fold.matches(".*meilleure?s? musiques?.*")) return true;
        if (fold.contains("top chansons") || fold.contains("top titres")) return true;
        return fold.contains("best of")
                && (fold.contains("mets") || fold.contains("joue") || fold.contains("lance")
                || fold.contains("veux") || fold.contains("playlist"));
    }

    private static String extractPlaylistSubject(String text) {
        String normalized = text
                .replaceAll("(?i)playslite", "playlist")
                .replaceAll("(?i)play liste", "playlist");

        Matcher playlist = Pattern.compile(
                "(?i)playlists?(?:e)?(?:\\s+(?:des?|de|du|d'|avec))?\\s+"
                        + "(?:(?:meilleures?|top|best)\\s+(?:chansons?|titres?|musiques?)\\s+(?:de|du|d'|des)?\\s+)?"
                        + "(.+?)(?:\\s+(?:sur|avec|dans)\\s+spotify)?\\s*$")
                .matcher(normalized);
        if (playlist.find()) return cleanPlaylistSubject(playlist.group(1));

        Matcher bestSongs = Pattern.compile(
                "(?i)(?:les?\\s+)?(?:meilleures?|top)\\s+(?:chansons?|titres?|musiques?)\\s+"
                        + "(?:de|du|d'|des)\\s+(.+?)(?:\\s+sur\\s+spotify)?\\s*$")
                .matcher(normalized);
        if (bestSongs.find()) return cleanPlaylistSubject(bestSongs.group(1));

        Matcher want = Pattern.compile(
                "(?i)(?:je\\s+(?:veux|voudrais)\\s+)?(?:une?\\s+)?playlists?(?:e)?\\s+"
                        + "(?:des?|de|du|d'|avec)?\\s*(.+?)(?:\\s+sur\\s+spotify)?\\s*$")
                .matcher(normalized);
        if (want.find()) return cleanPlaylistSubject(want.group(1));

        Matcher command = Pattern.compile(
                "(?i)(?:mets|joue|lance|fais|passe)(?:\\s+(?:moi|nous))?\\s+"
                        + "(?:une?\\s+)?playlists?(?:e)?\\s+(?:des?|de|du|d'|avec)?\\s*(.+)$")
                .matcher(normalized);
        if (command.find()) return cleanPlaylistSubject(command.group(1));

        return "";
    }

    private static String cleanPlaylistSubject(String raw) {
        if (raw == null) return "";
        String out = raw.trim();
        out = TRAILING_SPOTIFY.matcher(out).replaceAll("").trim();
        out = TRAILING_FILLER.matcher(out).replaceAll("").trim();
        out = out.replaceAll("(?i)^(?:des?|de|du|d'|les?|la|le)\\s+", "");
        out = out.replaceAll(
                "(?i)^(?:meilleures?|top|best)\\s+(?:chansons?|titres?|musiques?)\\s+(?:de|du|d'|des)?\\s*",
                "");
        return out.trim();
    }

    private static String stripCommandPrefixes(String text) {
        String out = text.trim();
        boolean changed;
        do {
            changed = false;
            String fold = SpeechInputNormalizer.fold(out);
            for (String prefix : COMMAND_PREFIXES) {
                if (fold.startsWith(prefix)) {
                    out = out.substring(prefix.length()).trim();
                    fold = SpeechInputNormalizer.fold(out);
                    changed = true;
                    break;
                }
            }
        } while (changed);
        return out;
    }

    private static String applyArtistAliases(String text) {
        if (text == null || text.isEmpty()) return text;
        String fold = SpeechInputNormalizer.fold(text);
        for (Map.Entry<String, String> entry : ARTIST_ALIASES.entrySet()) {
            if (fold.equals(entry.getKey())) return entry.getValue();
        }
        String bestKey = null;
        String bestValue = null;
        for (Map.Entry<String, String> entry : ARTIST_ALIASES.entrySet()) {
            if (containsWholePhrase(fold, entry.getKey())
                    && (bestKey == null || entry.getKey().length() > bestKey.length())) {
                bestKey = entry.getKey();
                bestValue = entry.getValue();
            }
        }
        if (bestValue == null) return text;
        if (fold.equals(bestKey)) return bestValue;
        return bestValue;
    }

    private static boolean containsWholePhrase(String fold, String phrase) {
        if (phrase == null || phrase.isEmpty()) return false;
        if (fold.equals(phrase)) return true;
        return fold.startsWith(phrase + " ")
                || fold.endsWith(" " + phrase)
                || fold.contains(" " + phrase + " ");
    }

    private static void alias(String heard, String meant) {
        ARTIST_ALIASES.put(SpeechInputNormalizer.fold(heard), meant);
    }
}
