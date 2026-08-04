package com.pegasuscorp.orbe.f1companion;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class WeekendSnapshotTest {

    @Test
    public void roundTripJson_andMarkdownContainsFacts() throws Exception {
        WeekendSnapshot s = new WeekendSnapshot();
        s.event = "Grand Prix de Royaume-Uni";
        s.date = "2026-07-05";
        s.circuit = "Silverstone";
        s.country = "United Kingdom";
        WeekendSnapshot.ResultRow w = new WeekendSnapshot.ResultRow();
        w.position = 1;
        w.driver = "Lando Norris";
        w.team = "McLaren";
        w.driverNumber = 4;
        w.points = 25;
        s.results.add(w);
        WeekendSnapshot.ResultRow p2 = new WeekendSnapshot.ResultRow();
        p2.position = 2;
        p2.driver = "Oscar Piastri";
        p2.team = "McLaren";
        p2.driverNumber = 81;
        s.results.add(p2);
        WeekendSnapshot.ResultRow p3 = new WeekendSnapshot.ResultRow();
        p3.position = 3;
        p3.driver = "Charles Leclerc";
        p3.team = "Ferrari";
        p3.driverNumber = 16;
        s.results.add(p3);
        WeekendSnapshot.GridRow g = new WeekendSnapshot.GridRow();
        g.position = 3;
        g.driver = "Lando Norris";
        g.driverNumber = 4;
        g.team = "McLaren";
        s.qualifying.add(g);
        DebriefBuilder.enrichKeyFacts(s);

        JSONObject json = s.toJson();
        WeekendSnapshot back = WeekendSnapshot.fromJson(json);
        assertEquals("Grand Prix de Royaume-Uni", back.event);
        assertEquals(3, back.results.size());
        assertTrue(back.toMarkdown().contains("FAITS"));
        assertTrue(back.toMarkdown().contains("Lando Norris"));
        assertFalse(s.keyFacts.isEmpty());
        assertTrue(DebriefBuilder.quickSpeech(s).contains("Podium"));
    }

    @Test
    public void positionDeltas_gainPlaces() {
        WeekendSnapshot s = new WeekendSnapshot();
        WeekendSnapshot.GridRow g = new WeekendSnapshot.GridRow();
        g.position = 6;
        g.driverNumber = 16;
        g.driver = "Charles Leclerc";
        s.qualifying.add(g);
        WeekendSnapshot.ResultRow r = new WeekendSnapshot.ResultRow();
        r.position = 3;
        r.driverNumber = 16;
        r.driver = "Charles Leclerc";
        s.results.add(r);
        assertTrue(s.positionDeltas().get(0).contains("+3"));
    }

    /**
     * Régression Hongrie 2026 : OpenF1 met {@code position:0} sur les DNF.
     * Un tri croissant naïf place les abandons avant P1 → podium = 3 derniers.
     */
    @Test
    public void podium_fromFrozenJson_ignoresLeadingDnfPositionZero() throws Exception {
        String frozen = ""
                + "{"
                + "  \"event\": \"Grand Prix de Hungary\","
                + "  \"session_key\": 11342,"
                + "  \"results\": ["
                + "    {\"position\":0,\"driver\":\"Oscar PIASTRI\",\"team\":\"McLaren\","
                + "     \"driver_number\":81,\"dnf\":true,\"dns\":false,\"dsq\":false,\"points\":0,\"laps\":55},"
                + "    {\"position\":0,\"driver\":\"Sergio PEREZ\",\"team\":\"Cadillac\","
                + "     \"driver_number\":11,\"dnf\":true,\"dns\":false,\"dsq\":false,\"points\":0,\"laps\":48},"
                + "    {\"position\":0,\"driver\":\"Valtteri BOTTAS\",\"team\":\"Cadillac\","
                + "     \"driver_number\":77,\"dnf\":true,\"dns\":false,\"dsq\":false,\"points\":0,\"laps\":13},"
                + "    {\"position\":1,\"driver\":\"Lando NORRIS\",\"team\":\"McLaren\","
                + "     \"driver_number\":1,\"dnf\":false,\"dns\":false,\"dsq\":false,\"points\":25,\"laps\":70},"
                + "    {\"position\":2,\"driver\":\"Max VERSTAPPEN\",\"team\":\"Red Bull Racing\","
                + "     \"driver_number\":3,\"dnf\":false,\"dns\":false,\"dsq\":false,\"points\":18,\"laps\":70},"
                + "    {\"position\":3,\"driver\":\"Kimi ANTONELLI\",\"team\":\"Mercedes\","
                + "     \"driver_number\":12,\"dnf\":false,\"dns\":false,\"dsq\":false,\"points\":15,\"laps\":70},"
                + "    {\"position\":4,\"driver\":\"Charles LECLERC\",\"team\":\"Ferrari\","
                + "     \"driver_number\":16,\"dnf\":false,\"dns\":false,\"dsq\":false,\"points\":12,\"laps\":70}"
                + "  ]"
                + "}";
        WeekendSnapshot snap = WeekendSnapshot.fromJson(new JSONObject(frozen));

        assertEquals("Lando NORRIS", snap.winner().driver);
        assertEquals("Lando NORRIS, Max VERSTAPPEN, Kimi ANTONELLI", snap.podiumLine());
        assertEquals(1, snap.results.get(0).position);
        assertEquals("Lando NORRIS", snap.results.get(0).driver);
        assertTrue(snap.results.get(snap.results.size() - 1).dnf
                || snap.results.get(snap.results.size() - 1).position == 0);

        DebriefBuilder.enrichKeyFacts(snap);
        assertTrue(snap.keyFacts.get(0).contains("Lando NORRIS"));
        assertFalse(snap.keyFacts.get(0).contains("PIASTRI"));
        assertTrue(snap.podiumLine().contains("NORRIS"));
        assertFalse(snap.podiumLine().contains("PIASTRI"));
        assertFalse(snap.podiumLine().contains("PEREZ"));
        assertFalse(snap.podiumLine().contains("BOTTAS"));
    }

    @Test
    public void sortResults_putsClassifiedBeforeDnf() {
        WeekendSnapshot s = new WeekendSnapshot();
        WeekendSnapshot.ResultRow dnf = new WeekendSnapshot.ResultRow();
        dnf.position = 0;
        dnf.dnf = true;
        dnf.driver = "DNF Guy";
        WeekendSnapshot.ResultRow p1 = new WeekendSnapshot.ResultRow();
        p1.position = 1;
        p1.driver = "Winner";
        WeekendSnapshot.ResultRow p2 = new WeekendSnapshot.ResultRow();
        p2.position = 2;
        p2.driver = "Second";
        // Ordre API : DNF puis classés
        s.results.add(dnf);
        s.results.add(p2);
        s.results.add(p1);
        s.sortResults();
        assertEquals("Winner", s.results.get(0).driver);
        assertEquals("Second", s.results.get(1).driver);
        assertEquals("DNF Guy", s.results.get(2).driver);
        assertEquals("Winner, Second", s.podiumLine());
    }
}
