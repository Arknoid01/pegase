package com.pegasuscorp.orbe.tools.knowledge;

import com.pegasuscorp.orbe.tools.HttpJson;

import android.content.Context;

import com.pegasuscorp.orbe.chat.ApiKeyStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Foot + F1 via API-Sports (api-football.com). Même clé pour les deux APIs.
 */
public final class ApiFootballService {

    private static final String FOOTBALL_BASE = "https://v3.football.api-sports.io";
    private static final String F1_BASE = "https://v1.formula-1.api-sports.io";

    private ApiFootballService() {}

    public static String fetch(Context ctx, String teamQuery, String type) throws Exception {
        String key = ApiKeyStore.getApiFootballKey(ctx);
        if (key.isEmpty()) return null;

        String resolved = SportsTool.resolveTeamQuery(teamQuery);
        if (SportsTool.isGenericF1Query(resolved) || SportsTool.isF1TeamQuery(resolved)) {
            return fetchF1(ctx, key, resolved, type);
        }
        return fetchFootball(ctx, key, resolved, type);
    }

    private static String fetchFootball(Context ctx, String key, String searchName, String type)
            throws Exception {
        JSONObject team = findFootballTeam(key, searchName);
        if (team == null) return null;

        int teamId = team.optInt("id", 0);
        String teamName = team.optString("name", searchName);
        if (teamId <= 0) return null;

        String path = "next".equals(type)
                ? "/fixtures?team=" + teamId + "&next=1"
                : "/fixtures?team=" + teamId + "&last=1";
        JSONObject res = footballGet(key, path);
        JSONArray response = res.optJSONArray("response");
        if (response == null || response.length() == 0) return null;

        return formatFootballFixture(response.getJSONObject(0), teamName, type);
    }

    private static String fetchF1(Context ctx, String key, String searchName, String type)
            throws Exception {
        int season = Calendar.getInstance().get(Calendar.YEAR);
        JSONObject res = f1Get(key, "/races?season=" + season + "&type=Race");
        JSONArray races = res.optJSONArray("response");
        if (races == null || races.length() == 0) {
            res = f1Get(key, "/races?season=" + (season - 1) + "&type=Race");
            races = res.optJSONArray("response");
        }
        if (races == null || races.length() == 0) return null;

        JSONObject race = pickF1Race(races, type);
        if (race == null) return null;

        String label = SportsTool.isGenericF1Query(searchName) ? "Formule 1" : searchName;
        return formatF1Race(label, type, race);
    }

    private static JSONObject findFootballTeam(String key, String searchName) throws Exception {
        String url = "/teams?search=" + URLEncoder.encode(searchName, "UTF-8");
        JSONObject res = footballGet(key, url);
        JSONArray response = res.optJSONArray("response");
        if (response == null || response.length() == 0) return null;

        String q = searchName.toLowerCase(Locale.ROOT);
        JSONObject best = null;
        int bestScore = Integer.MIN_VALUE;
        for (int i = 0; i < response.length(); i++) {
            JSONObject item = response.optJSONObject(i);
            if (item == null) continue;
            JSONObject team = item.optJSONObject("team");
            if (team == null) continue;
            int score = scoreFootballTeam(team, item.optJSONObject("venue"), q);
            if (score > bestScore) {
                bestScore = score;
                best = team;
            }
        }
        return best;
    }

    static int scoreFootballTeam(JSONObject team, JSONObject venue, String query) {
        String name = team.optString("name", "").toLowerCase(Locale.ROOT);
        String country = team.optString("country", "").toLowerCase(Locale.ROOT);
        int score = 0;
        if (name.equals(query)) score += 120;
        if (name.contains(query) || query.contains(name)) score += 70;
        if (country.contains("france")) score += 25;
        if (name.contains("paris") && query.contains("psg")) score += 40;
        if (name.contains("saint") && query.contains("etienne")) score += 40;
        return score;
    }

    private static JSONObject pickF1Race(JSONArray races, String type) throws Exception {
        long now = System.currentTimeMillis();
        JSONObject best = null;
        long bestDelta = Long.MAX_VALUE;

        for (int i = 0; i < races.length(); i++) {
            JSONObject race = races.getJSONObject(i);
            long raceMs = parseIsoDate(race.optString("date", ""));
            if (raceMs <= 0) continue;

            if ("next".equals(type)) {
                if (raceMs >= now) {
                    long delta = raceMs - now;
                    if (delta < bestDelta) {
                        bestDelta = delta;
                        best = race;
                    }
                }
            } else {
                if (raceMs <= now) {
                    long delta = now - raceMs;
                    if (delta < bestDelta) {
                        bestDelta = delta;
                        best = race;
                    }
                }
            }
        }
        if (best != null) return best;
        return races.getJSONObject("next".equals(type) ? races.length() - 1 : 0);
    }

    static String formatFootballFixture(JSONObject item, String teamName, String type) {
        JSONObject fixture = item.optJSONObject("fixture");
        JSONObject league = item.optJSONObject("league");
        JSONObject teams = item.optJSONObject("teams");
        JSONObject goals = item.optJSONObject("goals");

        String home = "?";
        String away = "?";
        if (teams != null) {
            JSONObject homeObj = teams.optJSONObject("home");
            JSONObject awayObj = teams.optJSONObject("away");
            if (homeObj != null) home = homeObj.optString("name", "?");
            if (awayObj != null) away = awayObj.optString("name", "?");
        }
        String date = formatDisplayDate(fixture != null ? fixture.optString("date", "") : "");
        String leagueName = league != null ? league.optString("name", "") : "";
        int homeGoals = -1;
        int awayGoals = -1;
        if (goals != null) {
            if (!goals.isNull("home")) homeGoals = goals.optInt("home", -1);
            if (!goals.isNull("away")) awayGoals = goals.optInt("away", -1);
        }
        boolean hasScore = homeGoals >= 0 && awayGoals >= 0;

        StringBuilder sb = new StringBuilder();
        if ("next".equals(type)) {
            sb.append("Prochain match de ").append(teamName).append(" : ")
                    .append(home).append(" vs ").append(away);
            if (!date.isEmpty()) sb.append(" le ").append(date);
            if (!leagueName.isEmpty()) sb.append(" (").append(leagueName).append(")");
        } else {
            sb.append("Dernier match de ").append(teamName).append(" : ")
                    .append(home);
            if (hasScore) {
                sb.append(" ").append(homeGoals).append(" - ")
                        .append(awayGoals).append(" ");
            } else {
                sb.append(" vs ");
            }
            sb.append(away);
            if (!date.isEmpty()) sb.append(" (").append(date).append(")");
            if (!leagueName.isEmpty()) sb.append(" — ").append(leagueName);
        }
        sb.append(".");
        return sb.toString();
    }

    static String formatF1Race(String teamName, String type, JSONObject race) {
        String competition = race.optJSONObject("competition") != null
                ? race.optJSONObject("competition").optString("name", "Grand Prix")
                : race.optString("competition", "Grand Prix");
        String circuit = race.optJSONObject("circuit") != null
                ? race.optJSONObject("circuit").optString("name", "")
                : "";
        String date = formatDisplayDate(race.optString("date", ""));

        StringBuilder sb = new StringBuilder();
        if ("next".equals(type)) {
            sb.append("Prochaine épreuve");
            if (!"Formule 1".equals(teamName)) sb.append(" pour ").append(teamName);
            sb.append(" : ").append(competition);
        } else {
            sb.append("Dernière épreuve");
            if (!"Formule 1".equals(teamName)) sb.append(" pour ").append(teamName);
            sb.append(" : ").append(competition);
        }
        if (!date.isEmpty()) sb.append(" le ").append(date);
        if (!circuit.isEmpty()) sb.append(" — ").append(circuit);
        sb.append(".");
        return sb.toString();
    }

    private static JSONObject footballGet(String key, String path) throws Exception {
        return HttpJson.get(FOOTBALL_BASE + path, apiHeaders(key));
    }

    private static JSONObject f1Get(String key, String path) throws Exception {
        return HttpJson.get(F1_BASE + path, apiHeaders(key));
    }

    private static Map<String, String> apiHeaders(String key) {
        Map<String, String> headers = new HashMap<>();
        headers.put("x-apisports-key", key);
        return headers;
    }

    private static long parseIsoDate(String iso) {
        if (iso == null || iso.length() < 10) return 0L;
        try {
            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US);
            fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
            String normalized = iso.replace("Z", "+0000");
            if (normalized.matches(".*[+-]\\d{2}:\\d{2}$")) {
                normalized = normalized.substring(0, normalized.length() - 3)
                        + normalized.substring(normalized.length() - 2);
            }
            return fmt.parse(normalized).getTime();
        } catch (Exception e) {
            try {
                return new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(iso.substring(0, 10)).getTime();
            } catch (Exception ignored) {
                return 0L;
            }
        }
    }

    private static String formatDisplayDate(String iso) {
        if (iso == null || iso.length() < 10) return "";
        String datePart = iso.substring(0, 10);
        try {
            SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd", Locale.FRANCE);
            SimpleDateFormat out = new SimpleDateFormat("d MMMM yyyy", Locale.FRENCH);
            return out.format(in.parse(datePart));
        } catch (Exception e) {
            return datePart;
        }
    }
}
