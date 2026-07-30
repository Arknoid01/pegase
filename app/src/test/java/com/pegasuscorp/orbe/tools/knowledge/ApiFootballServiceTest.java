package com.pegasuscorp.orbe.tools.knowledge;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class ApiFootballServiceTest {

    @Test
    public void formatFootballFixture_lastMatchIncludesScore() throws Exception {
        JSONObject item = new JSONObject(
                "{"
                        + "\"fixture\":{\"date\":\"2026-03-15T20:00:00+00:00\"},"
                        + "\"league\":{\"name\":\"Ligue 1\"},"
                        + "\"teams\":{\"home\":{\"name\":\"Paris Saint Germain\"},"
                        + "\"away\":{\"name\":\"Marseille\"}},"
                        + "\"goals\":{\"home\":2,\"away\":1}"
                        + "}");

        String reply = ApiFootballService.formatFootballFixture(item, "PSG", "last");

        assertTrue(reply.contains("Paris Saint Germain"));
        assertTrue(reply.contains("2 - 1"));
        assertTrue(reply.contains("Marseille"));
        assertTrue(reply.contains("Ligue 1"));
    }

    @Test
    public void formatF1Race_nextGrandPrix() throws Exception {
        JSONObject race = new JSONObject(
                "{"
                        + "\"competition\":{\"name\":\"Monaco Grand Prix\"},"
                        + "\"circuit\":{\"name\":\"Circuit de Monaco\"},"
                        + "\"date\":\"2026-05-24T13:00:00+00:00\""
                        + "}");

        String reply = ApiFootballService.formatF1Race("Ferrari", "next", race);

        assertTrue(reply.contains("Ferrari"));
        assertTrue(reply.contains("Monaco Grand Prix"));
        assertTrue(reply.contains("Circuit de Monaco"));
    }

    @Test
    public void scoreFootballTeam_prefersFrenchClub() throws Exception {
        JSONObject psg = new JSONObject("{\"name\":\"Paris Saint Germain\",\"country\":\"France\"}");
        JSONObject random = new JSONObject("{\"name\":\"Paris FC\",\"country\":\"France\"}");

        int psgScore = ApiFootballService.scoreFootballTeam(psg, null, "paris saint germain");
        int otherScore = ApiFootballService.scoreFootballTeam(random, null, "paris saint germain");

        assertTrue(psgScore > otherScore);
    }
}
