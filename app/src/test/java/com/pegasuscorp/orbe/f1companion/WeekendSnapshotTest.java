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
}
