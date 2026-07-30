package com.pegasuscorp.orbe.voice;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Locale;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class VoiceIntentRouterTest {

    @Test
    public void analyze_weatherRoutesToTool() {
        VoiceIntentRouter.RoutedIntent r =
                VoiceIntentRouter.analyze(null, "météo à Paris demain");
        assertNotNull(r.directToolJson);
        assertTrue(r.directToolJson.contains("weather"));
        assertTrue(r.confidence >= 0.5);
    }

    @Test
    public void analyze_nasaRoutesToTool() {
        VoiceIntentRouter.RoutedIntent r =
                VoiceIntentRouter.analyze(null, "nasa photo du jour");
        assertNotNull(r.directToolJson);
        assertTrue(r.directToolJson.contains("nasa"));
    }

    @Test
    public void analyze_smallTalkGoesToLlm() {
        VoiceIntentRouter.RoutedIntent r =
                VoiceIntentRouter.analyze(null, "salut comment ça va");
        assertNull(r.directToolJson);
        assertEquals("salut comment ça va", r.forLlm);
    }

    @Test
    public void analyze_sportsVagueAsksForTeamHint() {
        VoiceIntentRouter.RoutedIntent r =
                VoiceIntentRouter.analyze(null, "c'était comment le foot");
        assertNull(r.directToolJson);
        assertNotNull(r.intentHint);
        assertTrue(r.intentHint.toLowerCase(Locale.ROOT).contains("sport"));
    }

    @Test
    public void analyze_spotifyPlayRoutesWithNormalizedArtist() {
        VoiceIntentRouter.RoutedIntent r =
                VoiceIntentRouter.analyze(null, "mets du draft punk sur Spotify");
        assertNotNull(r.directToolJson);
        assertTrue(r.directToolJson.contains("spotify"));
        assertTrue(r.directToolJson.contains("Daft Punk"));
    }

    @Test
    public void analyze_spotifyPauseRoutesToTool() {
        VoiceIntentRouter.RoutedIntent r =
                VoiceIntentRouter.analyze(null, "pause la musique");
        assertNotNull(r.directToolJson);
        assertTrue(r.directToolJson.contains("\"action\":\"pause\""));
    }

    @Test
    public void analyze_spotifyPlaylistRoutesWithArtist() {
        VoiceIntentRouter.RoutedIntent r = VoiceIntentRouter.analyze(null,
                "je veux une playlist des meilleures chansons d'Orelsan");
        assertNotNull(r.directToolJson);
        assertTrue(r.directToolJson.contains("\"action\":\"playlist\""));
        assertTrue(r.directToolJson.contains("Orelsan"));
    }

    @Test
    public void analyze_navigationRoutesWithDestination() {
        VoiceIntentRouter.RoutedIntent r =
                VoiceIntentRouter.analyze(null, "itinéraire pour aller à Lyon");
        assertNotNull(r.directToolJson);
        assertTrue(r.directToolJson.contains("navigation"));
        assertTrue(r.directToolJson.contains("Lyon"));
    }

    @Test
    public void analyze_flashlightRoutesOnCommand() {
        VoiceIntentRouter.RoutedIntent r =
                VoiceIntentRouter.analyze(null, "allume la lampe torche");
        assertNotNull(r.directToolJson);
        assertTrue(r.directToolJson.contains("flashlight"));
        assertTrue(r.directToolJson.contains("\"action\":\"on\""));
    }

    @Test
    public void analyze_situationRoutineRoutesComposite() {
        VoiceIntentRouter.RoutedIntent r =
                VoiceIntentRouter.analyze(null, "comme d'habitude");
        assertNotNull(r.directToolJson);
        assertTrue(r.directToolJson.contains("composite"));
    }

    @Test
    public void analyze_callRoutesWithContact() {
        VoiceIntentRouter.RoutedIntent r =
                VoiceIntentRouter.analyze(null, "appelle maman");
        assertNotNull(r.directToolJson);
        assertTrue(r.directToolJson.contains("call"));
        assertTrue(r.directToolJson.contains("maman"));
    }

    @Test
    public void analyze_wifiStatusRoutes() {
        VoiceIntentRouter.RoutedIntent r =
                VoiceIntentRouter.analyze(null, "le wifi est activé ?");
        assertNotNull(r.directToolJson);
        assertTrue(r.directToolJson.contains("connectivity"));
        assertTrue(r.directToolJson.contains("wifi"));
    }
}
