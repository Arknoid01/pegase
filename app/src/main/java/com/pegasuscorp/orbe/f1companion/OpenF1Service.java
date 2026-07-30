package com.pegasuscorp.orbe.f1companion;

import com.pegasuscorp.orbe.tools.HttpJson;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Client OpenF1 (historique + live, sans clé) — sessions, résultats, grille, race control.
 */
public final class OpenF1Service {

    private static final String BASE = "https://api.openf1.org/v1";
    /** Grace après date_end pour chequered / derniers messages. */
    public static final long LIVE_GRACE_MS = 20L * 60L * 1000L;

    private OpenF1Service() {}

    /** Session Race en cours (ou grace post-arrivée), sinon null. */
    public static JSONObject findLiveRaceSession() throws Exception {
        JSONObject latest = fetchLatestSession();
        if (isLiveRaceWindow(latest, System.currentTimeMillis())) return latest;
        // Parfois "latest" est déjà une autre session : scan year Race
        int year = Calendar.getInstance().get(Calendar.YEAR);
        JSONArray sessions = get("/sessions?year=" + year + "&session_name=Race");
        long now = System.currentTimeMillis();
        JSONObject best = null;
        long bestStart = Long.MIN_VALUE;
        for (int i = 0; i < sessions.length(); i++) {
            JSONObject s = sessions.optJSONObject(i);
            if (!isLiveRaceWindow(s, now)) continue;
            long start = parseIso(s.optString("date_start", ""));
            if (start >= bestStart) {
                bestStart = start;
                best = s;
            }
        }
        return best;
    }

    public static boolean isLiveRaceWindow(JSONObject session, long nowMs) {
        if (session == null) return false;
        if (session.optBoolean("is_cancelled", false)) return false;
        String name = session.optString("session_name", "");
        String type = session.optString("session_type", "");
        if (!"Race".equalsIgnoreCase(name) && !"Race".equalsIgnoreCase(type)) return false;
        long start = parseIso(session.optString("date_start", ""));
        long end = parseIso(session.optString("date_end", ""));
        if (start <= 0) return false;
        // Ouvre 5 min avant le départ
        if (nowMs < start - 5L * 60L * 1000L) return false;
        if (end > 0) return nowMs <= end + LIVE_GRACE_MS;
        // Pas de date_end : fenêtre 3 h
        return nowMs <= start + 3L * 60L * 60L * 1000L;
    }

    public static JSONObject fetchLatestSession() throws Exception {
        JSONArray arr = get("/sessions?session_key=latest");
        if (arr.length() == 0) return null;
        return arr.optJSONObject(0);
    }

    public static List<JSONObject> fetchRaceControl(int sessionKey) throws Exception {
        JSONArray arr = get("/race_control?session_key=" + sessionKey);
        List<JSONObject> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null) out.add(o);
        }
        return out;
    }

    public static List<JSONObject> fetchPositions(int sessionKey) throws Exception {
        JSONArray arr = get("/position?session_key=" + sessionKey);
        List<JSONObject> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null) out.add(o);
        }
        return out;
    }

    public static Map<Integer, DriverInfo> loadDriversPublic(int sessionKey) throws Exception {
        return loadDrivers(sessionKey);
    }

    /** Brief texte pour outil / voix. */
    public static String liveBrief(JSONObject session, Map<Integer, Integer> positions,
            Map<Integer, DriverInfo> drivers, List<String> recentEvents) {
        if (session == null) return "Aucune course live en ce moment.";
        String loc = session.optString("location", session.optString("circuit_short_name", "GP"));
        String country = session.optString("country_name", "");
        StringBuilder sb = new StringBuilder();
        sb.append("Live — Grand Prix");
        if (!country.isEmpty()) sb.append(" de ").append(country);
        else sb.append(" · ").append(loc);
        sb.append(".\n");
        if (positions != null && !positions.isEmpty() && drivers != null) {
            List<Map.Entry<Integer, Integer>> ranked = new ArrayList<>(positions.entrySet());
            ranked.sort(Comparator.comparingInt(Map.Entry::getValue));
            sb.append("Top : ");
            int n = 0;
            for (Map.Entry<Integer, Integer> e : ranked) {
                if (n >= 3) break;
                DriverInfo d = drivers.get(e.getKey());
                String name = d != null && !d.name.isEmpty() ? d.name : "#" + e.getKey();
                if (n > 0) sb.append(", ");
                sb.append("P").append(e.getValue()).append(" ").append(name);
                n++;
            }
            sb.append(".\n");
        }
        if (recentEvents != null && !recentEvents.isEmpty()) {
            sb.append("Récents : ");
            for (int i = 0; i < Math.min(3, recentEvents.size()); i++) {
                if (i > 0) sb.append(" · ");
                sb.append(recentEvents.get(i));
            }
            sb.append('.');
        }
        return sb.toString().trim();
    }

    /** Construit la fiche du dernier Grand Prix déjà couru (Race). */
    public static WeekendSnapshot fetchLatestRaceWeekend() throws Exception {
        JSONObject session = pickLatestPastRaceSession();
        if (session == null) {
            throw new IllegalStateException("Aucune course OpenF1 trouvée.");
        }
        return buildFromRaceSession(session);
    }

    static WeekendSnapshot buildFromRaceSession(JSONObject session) throws Exception {
        WeekendSnapshot snap = new WeekendSnapshot();
        snap.sessionKey = session.optInt("session_key", 0);
        snap.meetingKey = session.optInt("meeting_key", 0);
        snap.year = session.optInt("year", Calendar.getInstance().get(Calendar.YEAR));
        snap.circuit = session.optString("circuit_short_name", "");
        snap.country = session.optString("country_name", "");
        snap.date = session.optString("date_start", "");
        if (snap.date.length() >= 10) snap.date = snap.date.substring(0, 10);
        String loc = session.optString("location", snap.circuit);
        snap.event = "Grand Prix — " + (loc.isEmpty() ? snap.circuit : loc);
        if (!snap.country.isEmpty()) {
            snap.event = "Grand Prix de " + snap.country;
        }
        snap.sources.add("OpenF1");
        snap.updatedAtMs = System.currentTimeMillis();

        if (snap.sessionKey <= 0) return snap;

        Map<Integer, DriverInfo> drivers = loadDrivers(snap.sessionKey);
        fillResults(snap, drivers);
        fillGrid(snap, drivers);
        fillRaceControl(snap);
        fillPitSummary(snap, drivers);
        DebriefBuilder.enrichKeyFacts(snap);
        return snap;
    }

    private static JSONObject pickLatestPastRaceSession() throws Exception {
        int year = Calendar.getInstance().get(Calendar.YEAR);
        JSONObject best = null;
        long bestMs = Long.MIN_VALUE;
        long now = System.currentTimeMillis();
        for (int y = year; y >= year - 1; y--) {
            JSONArray sessions = get("/sessions?year=" + y + "&session_name=Race");
            for (int i = 0; i < sessions.length(); i++) {
                JSONObject s = sessions.optJSONObject(i);
                if (s == null || s.optBoolean("is_cancelled", false)) continue;
                long ms = parseIso(s.optString("date_start", ""));
                if (ms <= 0 || ms > now) continue;
                if (ms > bestMs) {
                    bestMs = ms;
                    best = s;
                }
            }
            if (best != null) break;
        }
        return best;
    }

    private static void fillResults(WeekendSnapshot snap, Map<Integer, DriverInfo> drivers)
            throws Exception {
        JSONArray arr = get("/session_result?session_key=" + snap.sessionKey);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            WeekendSnapshot.ResultRow r = new WeekendSnapshot.ResultRow();
            r.position = o.optInt("position", 0);
            r.driverNumber = o.optInt("driver_number", 0);
            r.dnf = o.optBoolean("dnf", false);
            r.dns = o.optBoolean("dns", false);
            r.dsq = o.optBoolean("dsq", false);
            r.points = o.optDouble("points", 0);
            r.laps = o.optInt("number_of_laps", 0);
            Object gap = o.opt("gap_to_leader");
            if (gap != null && !(gap instanceof JSONArray)) {
                r.gap = String.valueOf(gap);
            }
            DriverInfo d = drivers.get(r.driverNumber);
            if (d != null) {
                r.driver = d.name;
                r.team = d.team;
            }
            snap.results.add(r);
        }
        snap.results.sort((a, b) -> Integer.compare(a.position, b.position));
    }

    private static void fillGrid(WeekendSnapshot snap, Map<Integer, DriverInfo> drivers)
            throws Exception {
        JSONArray arr;
        try {
            arr = get("/starting_grid?session_key=" + snap.sessionKey);
        } catch (Exception e) {
            return;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            WeekendSnapshot.GridRow g = new WeekendSnapshot.GridRow();
            g.position = o.optInt("position", 0);
            g.driverNumber = o.optInt("driver_number", 0);
            DriverInfo d = drivers.get(g.driverNumber);
            if (d != null) {
                g.driver = d.name;
                g.team = d.team;
            }
            snap.qualifying.add(g);
        }
        snap.qualifying.sort((a, b) -> Integer.compare(a.position, b.position));
    }

    private static void fillRaceControl(WeekendSnapshot snap) throws Exception {
        JSONArray arr;
        try {
            arr = get("/race_control?session_key=" + snap.sessionKey);
        } catch (Exception e) {
            return;
        }
        int sc = 0;
        int vsc = 0;
        int red = 0;
        java.util.LinkedHashSet<String> notable = new java.util.LinkedHashSet<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String cat = o.optString("category", "").toUpperCase(Locale.ROOT);
            String msg = o.optString("message", "").trim();
            String flag = o.optString("flag", "").toUpperCase(Locale.ROOT);
            if (cat.contains("SAFETY") || msg.toLowerCase(Locale.ROOT).contains("safety car")
                    || flag.contains("SAFETY")) {
                sc++;
                if (!msg.isEmpty() && notable.size() < 8) notable.add(msg);
            } else if (msg.toLowerCase(Locale.ROOT).contains("virtual safety")
                    || flag.contains("VSC")) {
                vsc++;
            } else if (flag.contains("RED") || cat.contains("FLAG") && flag.contains("RED")) {
                red++;
                if (!msg.isEmpty() && notable.size() < 8) notable.add(msg);
            } else if ((cat.contains("PENALTY") || msg.toLowerCase(Locale.ROOT).contains("penalt"))
                    && !msg.isEmpty() && notable.size() < 8) {
                notable.add(msg);
            }
        }
        if (sc > 0) snap.incidents.add("Safety Car : " + sc + " intervention(s)");
        if (vsc > 0) snap.incidents.add("Virtual Safety Car : " + vsc + " période(s)");
        if (red > 0) snap.incidents.add("Drapeau rouge : " + red);
        for (String n : notable) snap.incidents.add(n);
    }

    private static void fillPitSummary(WeekendSnapshot snap, Map<Integer, DriverInfo> drivers)
            throws Exception {
        JSONArray arr;
        try {
            arr = get("/pit?session_key=" + snap.sessionKey);
        } catch (Exception e) {
            return;
        }
        Map<Integer, Integer> pits = new HashMap<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            int num = o.optInt("driver_number", 0);
            if (num <= 0) continue;
            pits.put(num, pits.getOrDefault(num, 0) + 1);
        }
        // Top 6 podium + quelques arrêts intéressants
        int listed = 0;
        for (WeekendSnapshot.ResultRow r : snap.results) {
            if (listed >= 8) break;
            Integer n = pits.get(r.driverNumber);
            if (n == null) continue;
            String name = r.driver.isEmpty() ? "#" + r.driverNumber : r.driver;
            snap.strategies.add(name + " : " + n + " arrêt(s)");
            listed++;
        }
    }

    private static Map<Integer, DriverInfo> loadDrivers(int sessionKey) throws Exception {
        Map<Integer, DriverInfo> map = new HashMap<>();
        JSONArray arr = get("/drivers?session_key=" + sessionKey);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            int num = o.optInt("driver_number", 0);
            if (num <= 0) continue;
            String name = o.optString("full_name", "");
            if (name.isEmpty()) {
                name = (o.optString("first_name", "") + " " + o.optString("last_name", "")).trim();
            }
            if (name.isEmpty()) name = o.optString("name_acronym", "#" + num);
            DriverInfo d = new DriverInfo();
            d.name = name;
            d.team = o.optString("team_name", "");
            map.put(num, d);
        }
        return map;
    }

    private static JSONArray get(String path) throws Exception {
        return HttpJson.getArray(BASE + path, null, 12_000, 25_000);
    }

    private static long parseIso(String iso) {
        return parseIsoPublic(iso);
    }

    public static long parseIsoPublic(String iso) {
        if (iso == null || iso.isEmpty()) return 0L;
        try {
            // 2026-07-05T14:00:00+00:00
            String t = iso.replace("Z", "+00:00");
            if (t.length() >= 19) {
                java.text.SimpleDateFormat fmt =
                        new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
                fmt.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                return fmt.parse(t.substring(0, 19)).getTime();
            }
        } catch (Exception ignored) {
        }
        return 0L;
    }

    public static final class DriverInfo {
        public String name = "";
        public String team = "";
    }
}
