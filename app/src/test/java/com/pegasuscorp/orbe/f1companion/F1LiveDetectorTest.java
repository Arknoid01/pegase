package com.pegasuscorp.orbe.f1companion;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class F1LiveDetectorTest {

    @Test
    public void detectsSafetyCarDeployed() throws Exception {
        JSONObject o = new JSONObject()
                .put("category", "SafetyCar")
                .put("flag", "")
                .put("date", "2026-07-19T13:05:25+00:00")
                .put("message", "SAFETY CAR DEPLOYED");
        FavoriteTeamsStore.TeamDef ferrari = FavoriteTeamsStore.find("ferrari");
        List<F1LiveEvent> ev = F1LiveDetector.fromRaceControl(
                Collections.singletonList(o),
                Collections.emptyMap(),
                Collections.singletonList(ferrari),
                0L);
        assertEquals(1, ev.size());
        assertEquals(F1LiveEvent.Kind.SAFETY_CAR, ev.get(0).kind);
    }

    @Test
    public void ignoresSafetyCarEnding() throws Exception {
        JSONObject o = new JSONObject()
                .put("category", "SafetyCar")
                .put("message", "SAFETY CAR IN THIS LAP")
                .put("date", "2026-07-19T13:12:32+00:00");
        List<F1LiveEvent> ev = F1LiveDetector.fromRaceControl(
                Collections.singletonList(o), null,
                Collections.singletonList(FavoriteTeamsStore.find("ferrari")), 0L);
        assertTrue(ev.isEmpty());
    }

    @Test
    public void penaltyOnlyForFollowedTeam() throws Exception {
        Map<Integer, OpenF1Service.DriverInfo> drivers = new HashMap<>();
        OpenF1Service.DriverInfo ham = new OpenF1Service.DriverInfo();
        ham.name = "Lewis Hamilton";
        ham.team = "Ferrari";
        drivers.put(44, ham);

        JSONObject o = new JSONObject()
                .put("category", "Other")
                .put("message", "FIA STEWARDS: 5 SECOND TIME PENALTY FOR CAR 44 (HAM)")
                .put("date", "2026-07-19T13:23:11+00:00")
                .put("driver_number", 44);

        List<F1LiveEvent> hit = F1LiveDetector.fromRaceControl(
                Collections.singletonList(o), drivers,
                Collections.singletonList(FavoriteTeamsStore.find("ferrari")), 0L);
        assertEquals(1, hit.size());
        assertEquals(F1LiveEvent.Kind.PENALTY, hit.get(0).kind);

        List<F1LiveEvent> miss = F1LiveDetector.fromRaceControl(
                Collections.singletonList(o), drivers,
                Collections.singletonList(FavoriteTeamsStore.find("mclaren")), 0L);
        assertTrue(miss.isEmpty());
    }

    @Test
    public void positionJumpForFavorite() {
        Map<Integer, OpenF1Service.DriverInfo> drivers = new HashMap<>();
        OpenF1Service.DriverInfo lec = new OpenF1Service.DriverInfo();
        lec.name = "Charles Leclerc";
        lec.team = "Ferrari";
        drivers.put(16, lec);

        Map<Integer, Integer> prev = new HashMap<>();
        prev.put(16, 8);
        Map<Integer, Integer> now = new HashMap<>();
        now.put(16, 4);

        List<F1LiveEvent> ev = F1LiveDetector.fromPositionJumps(
                prev, now, drivers,
                Collections.singletonList(FavoriteTeamsStore.find("ferrari")),
                System.currentTimeMillis());
        assertEquals(1, ev.size());
        assertEquals(F1LiveEvent.Kind.BIG_MOVE, ev.get(0).kind);
        assertTrue(ev.get(0).body.contains("P8"));
        assertTrue(ev.get(0).body.contains("P4"));
    }

    @Test
    public void liveRaceWindow_usesGrace() throws Exception {
        JSONObject s = new JSONObject()
                .put("session_name", "Race")
                .put("session_type", "Race")
                .put("date_start", "2026-07-19T13:00:00+00:00")
                .put("date_end", "2026-07-19T15:00:00+00:00")
                .put("is_cancelled", false);
        long during = OpenF1Service.parseIsoPublic("2026-07-19T14:00:00+00:00");
        long afterGrace = OpenF1Service.parseIsoPublic("2026-07-19T15:30:00+00:00");
        assertTrue(OpenF1Service.isLiveRaceWindow(s, during));
        assertFalse(OpenF1Service.isLiveRaceWindow(s, afterGrace));
    }
}
