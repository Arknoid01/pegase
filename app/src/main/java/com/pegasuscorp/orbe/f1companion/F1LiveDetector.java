package com.pegasuscorp.orbe.f1companion;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Transforme race_control (+ positions) en événements live actionnables.
 */
public final class F1LiveDetector {

    private static final Pattern CAR_NUM = Pattern.compile("\\bCAR\\s+(\\d+)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern CODE = Pattern.compile("\\(([A-Z]{3})\\)");

    private F1LiveDetector() {}

    public static List<F1LiveEvent> fromRaceControl(
            List<JSONObject> messages,
            Map<Integer, OpenF1Service.DriverInfo> drivers,
            List<FavoriteTeamsStore.TeamDef> favorites,
            long minDateMsExclusive) {
        List<F1LiveEvent> out = new ArrayList<>();
        if (messages == null) return out;
        for (JSONObject o : messages) {
            if (o == null) continue;
            long at = OpenF1Service.parseIsoPublic(o.optString("date", ""));
            if (at > 0 && at <= minDateMsExclusive) continue;
            F1LiveEvent ev = classify(o, drivers, favorites, at);
            if (ev != null) out.add(ev);
        }
        out.sort((a, b) -> Integer.compare(b.priority, a.priority));
        return out;
    }

    /**
     * Gros saut de place (≥3) pour une écurie suivie.
     */
    public static List<F1LiveEvent> fromPositionJumps(
            Map<Integer, Integer> previous,
            Map<Integer, Integer> current,
            Map<Integer, OpenF1Service.DriverInfo> drivers,
            List<FavoriteTeamsStore.TeamDef> favorites,
            long atMs) {
        List<F1LiveEvent> out = new ArrayList<>();
        if (previous == null || previous.isEmpty() || current == null || current.isEmpty()) {
            return out;
        }
        for (Map.Entry<Integer, Integer> e : current.entrySet()) {
            int num = e.getKey();
            Integer now = e.getValue();
            Integer was = previous.get(num);
            if (now == null || was == null || now <= 0 || was <= 0) continue;
            int delta = was - now; // positif = a gagné des places
            if (Math.abs(delta) < 3) continue;
            OpenF1Service.DriverInfo d = drivers != null ? drivers.get(num) : null;
            if (d == null || !teamFollowed(d.team, favorites)) continue;
            String name = d.name.isEmpty() ? "#" + num : d.name;
            String team = d.team;
            String body;
            String id;
            if (delta > 0) {
                body = name + " (" + team + ") passe de P" + was + " à P" + now + ".";
                id = "move_up_" + num + "_" + was + "_" + now;
            } else {
                body = name + " (" + team + ") descend de P" + was + " à P" + now + ".";
                id = "move_down_" + num + "_" + was + "_" + now;
            }
            out.add(new F1LiveEvent(F1LiveEvent.Kind.BIG_MOVE, id,
                    "Pégase · " + shortTeam(team), body, team, 40, atMs));
        }
        out.sort((a, b) -> Integer.compare(b.priority, a.priority));
        return out;
    }

    static F1LiveEvent classify(JSONObject o,
            Map<Integer, OpenF1Service.DriverInfo> drivers,
            List<FavoriteTeamsStore.TeamDef> favorites,
            long atMs) {
        String cat = o.optString("category", "").toUpperCase(Locale.ROOT);
        String flag = o.optString("flag", "").toUpperCase(Locale.ROOT);
        String msg = o.optString("message", "").trim();
        String msgU = msg.toUpperCase(Locale.ROOT);
        String date = o.optString("date", "");
        String baseId = date + "|" + cat + "|" + flag + "|" + msg;

        if (cat.contains("SAFETY") || msgU.contains("SAFETY CAR DEPLOYED")) {
            if (msgU.contains("IN THIS LAP") || msgU.contains("ENDING") || msgU.contains("END ")) {
                return null;
            }
            if (msgU.contains("VSC DEPLOYED") || (cat.contains("SAFETY") && msgU.contains("VSC")
                    && msgU.contains("DEPLOY"))) {
                return new F1LiveEvent(F1LiveEvent.Kind.VSC, "vsc|" + baseId,
                        "Pégase · Live F1", "Virtual Safety Car déployée.", "", 80, atMs);
            }
            if (msgU.contains("SAFETY CAR") || cat.equals("SAFETYCAR")) {
                return new F1LiveEvent(F1LiveEvent.Kind.SAFETY_CAR, "sc|" + baseId,
                        "Pégase · Live F1", "Safety Car déployée.", "", 90, atMs);
            }
        }
        if (flag.contains("RED") || msgU.contains("RED FLAG")) {
            return new F1LiveEvent(F1LiveEvent.Kind.RED_FLAG, "red|" + baseId,
                    "Pégase · Live F1", "Drapeau rouge.", "", 95, atMs);
        }
        if (msgU.contains("RETIRED") || msgU.contains("STOPPED ON TRACK")
                || msgU.contains("OUT OF THE RACE")) {
            Integer car = extractCar(msg, o);
            OpenF1Service.DriverInfo d = car != null && drivers != null ? drivers.get(car) : null;
            if (d != null && !teamFollowed(d.team, favorites)) return null;
            String who = d != null
                    ? (d.name.isEmpty() ? "#" + car : d.name) + " (" + d.team + ")"
                    : (car != null ? "la voiture #" + car : "une voiture");
            String team = d != null ? d.team : "";
            return new F1LiveEvent(F1LiveEvent.Kind.RETIREMENT, "ret|" + baseId,
                    "Pégase · " + (team.isEmpty() ? "Live F1" : shortTeam(team)),
                    who + " abandonne.", team, 75, atMs);
        }
        if (msgU.contains("PENALTY") || msgU.contains("TIME PENALTY")) {
            Integer car = extractCar(msg, o);
            OpenF1Service.DriverInfo d = car != null && drivers != null ? drivers.get(car) : null;
            if (d == null || !teamFollowed(d.team, favorites)) return null;
            String who = d.name.isEmpty() ? "#" + car : d.name;
            String shortMsg = msg.length() > 120 ? msg.substring(0, 117) + "…" : msg;
            return new F1LiveEvent(F1LiveEvent.Kind.PENALTY, "pen|" + baseId,
                    "Pégase · " + shortTeam(d.team),
                    who + " : " + shortMsg, d.team, 70, atMs);
        }
        if (flag.contains("CHEQUERED") || msgU.contains("SESSION FINISHED")
                || msgU.contains("CHEQUERED FLAG")) {
            return new F1LiveEvent(F1LiveEvent.Kind.CHEQUERED, "end|" + baseId,
                    "Pégase · Live F1", "Drapeau à damier — la course est terminée.", "", 60, atMs);
        }
        return null;
    }

    private static Integer extractCar(String msg, JSONObject o) {
        int fromObj = o.optInt("driver_number", 0);
        if (fromObj > 0) return fromObj;
        if (msg == null) return null;
        Matcher m = CAR_NUM.matcher(msg);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (Exception ignored) {}
        }
        return null;
    }

    static boolean teamFollowed(String teamName, List<FavoriteTeamsStore.TeamDef> favorites) {
        if (favorites == null || favorites.isEmpty()) return true; // pas de filtre → tout
        if (teamName == null || teamName.isEmpty()) return false;
        String hay = teamName.toLowerCase(Locale.ROOT);
        for (FavoriteTeamsStore.TeamDef t : favorites) {
            for (String a : FavoriteTeamsStore.aliasesFor(t)) {
                if (a != null && hay.contains(a.toLowerCase(Locale.ROOT))) return true;
            }
            if (hay.contains(t.label.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private static String shortTeam(String team) {
        if (team == null || team.isEmpty()) return "Live F1";
        // "Scuderia Ferrari" → Ferrari si possible
        for (FavoriteTeamsStore.TeamDef t : FavoriteTeamsStore.CATALOG) {
            if (teamFollowed(team, java.util.Collections.singletonList(t))) return t.label;
        }
        return team.length() > 18 ? team.substring(0, 18) : team;
    }

    /** Dernière position connue par pilote (liste chronologique OpenF1). */
    public static Map<Integer, Integer> latestPositions(List<JSONObject> positionRows) {
        Map<Integer, Integer> map = new HashMap<>();
        if (positionRows == null) return map;
        for (JSONObject o : positionRows) {
            if (o == null) continue;
            int num = o.optInt("driver_number", 0);
            int pos = o.optInt("position", 0);
            if (num > 0 && pos > 0) map.put(num, pos);
        }
        return map;
    }
}
