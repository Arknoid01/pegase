package com.pegasuscorp.orbe.spotify;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class SpotifyQueryNormalizerTest {

    @Test
    public void normalize_stripsFrenchPlayPhrases() {
        assertEquals("Daft Punk",
                SpotifyQueryNormalizer.normalize(null, "mets moi du Daft Punk sur Spotify"));
        assertEquals("Stromae",
                SpotifyQueryNormalizer.normalize(null, "joue de la musique de Stromae"));
    }

    @Test
    public void normalize_fixesCommonSttArtistMistakes() {
        assertEquals("Daft Punk",
                SpotifyQueryNormalizer.normalize(null, "draft punk"));
        assertEquals("Coldplay",
                SpotifyQueryNormalizer.normalize(null, "cold play"));
        assertEquals("The Weeknd",
                SpotifyQueryNormalizer.normalize(null, "week end"));
    }

    @Test
    public void extractArtistFromSpeech_handlesOralForms() {
        assertEquals("Daft Punk",
                SpotifyQueryNormalizer.extractArtistFromSpeech("mets du draft punk"));
        assertEquals("Justice",
                SpotifyQueryNormalizer.extractArtistFromSpeech("la musique de Justice"));
        assertEquals("Orelsan",
                SpotifyQueryNormalizer.extractArtistFromSpeech("joue Orelsan sur Spotify"));
    }

    @Test
    public void searchVariants_includesAliasValue() {
        assertTrue(SpotifyQueryNormalizer.searchVariants(null, "draft punk")
                .contains("Daft Punk"));
    }

    @Test
    public void detectPlaylistRequest_extractsArtistFromFrenchPhrase() {
        SpotifyQueryNormalizer.PlaylistRequest r = SpotifyQueryNormalizer
                .detectPlaylistRequest("je veux une playlist des meilleures chansons d'Orelsan");
        assertNotNull(r);
        assertEquals("Orelsan", r.subject);
    }

    @Test
    public void detectPlaylistRequest_handlesSttTypos() {
        SpotifyQueryNormalizer.PlaylistRequest r = SpotifyQueryNormalizer
                .detectPlaylistRequest("une playslite des meilleures chanson d'orelsan");
        assertNotNull(r);
        assertEquals("Orelsan", r.subject);
    }

    @Test
    public void detectPlaylistRequest_handlesBestSongsWithoutPlaylistWord() {
        SpotifyQueryNormalizer.PlaylistRequest r = SpotifyQueryNormalizer
                .detectPlaylistRequest("mets les meilleures chansons de Stromae");
        assertNotNull(r);
        assertEquals("Stromae", r.subject);
    }

    @Test
    public void playlistSearchVariants_includesEditorialStyleQueries() {
        List<String> variants = SpotifyQueryNormalizer.playlistSearchVariants(null, "Orelsan");
        assertTrue(variants.contains("This Is Orelsan"));
        assertTrue(variants.contains("Orelsan greatest hits"));
    }
}
