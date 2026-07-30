package com.pegasuscorp.orbe.tools.knowledge;

import com.pegasuscorp.orbe.tools.HttpJson;

import com.pegasuscorp.orbe.tools.ToolTag;

import com.pegasuscorp.orbe.tools.ToolResult;

import com.pegasuscorp.orbe.tools.Tool;
import com.pegasuscorp.orbe.tools.ToolCallback;

import android.content.Context;

import com.pegasuscorp.orbe.chat.ApiKeyStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Résultats sportifs : API-Football (prioritaire), Tavily, puis TheSportsDB.
 */
public final class SportsTool implements Tool {

    private static final String F1_LEAGUE_ID = "4370";

    private static final Map<String, String> TEAM_ALIASES = new HashMap<>();

    static {
        // Foot
        TEAM_ALIASES.put("psg", "Paris Saint Germain");
        TEAM_ALIASES.put("paris sg", "Paris Saint Germain");
        TEAM_ALIASES.put("paris saint-germain", "Paris Saint Germain");
        TEAM_ALIASES.put("paris saint germain", "Paris Saint Germain");
        TEAM_ALIASES.put("om", "Marseille");
        TEAM_ALIASES.put("ol", "Lyon");
        TEAM_ALIASES.put("olympique lyonnais", "Lyon");
        TEAM_ALIASES.put("asm", "Monaco");
        TEAM_ALIASES.put("asse", "Saint Etienne");
        TEAM_ALIASES.put("st etienne", "Saint Etienne");
        TEAM_ALIASES.put("saint-etienne", "Saint Etienne");
        TEAM_ALIASES.put("losc", "Lille");
        TEAM_ALIASES.put("rc lens", "Lens");
        TEAM_ALIASES.put("rennes", "Rennes");
        TEAM_ALIASES.put("stade rennais", "Rennes");
        TEAM_ALIASES.put("nice", "Nice");
        TEAM_ALIASES.put("ogc nice", "Nice");
        TEAM_ALIASES.put("france", "France");
        TEAM_ALIASES.put("équipe de france", "France");
        TEAM_ALIASES.put("equipe de france", "France");
        TEAM_ALIASES.put("les bleus", "France");
        // F1
        TEAM_ALIASES.put("f1", "F1");
        TEAM_ALIASES.put("formule 1", "F1");
        TEAM_ALIASES.put("formula 1", "F1");
        TEAM_ALIASES.put("formule1", "F1");
        TEAM_ALIASES.put("grand prix", "F1");
        TEAM_ALIASES.put("ferrari", "Ferrari");
        TEAM_ALIASES.put("red bull", "Red Bull");
        TEAM_ALIASES.put("red bull racing", "Red Bull");
        TEAM_ALIASES.put("mclaren", "McLaren");
        TEAM_ALIASES.put("mercedes", "Mercedes");
        TEAM_ALIASES.put("alpine", "Alpine");
        TEAM_ALIASES.put("aston martin", "Aston Martin");
        TEAM_ALIASES.put("haas", "Haas");
        TEAM_ALIASES.put("racing bulls", "Racing Bulls");
        TEAM_ALIASES.put("audi", "Audi");
        TEAM_ALIASES.put("cadillac", "Cadillac");
        TEAM_ALIASES.put("sauber", "Sauber");
        TEAM_ALIASES.put("kick sauber", "Sauber");
    }

    private final ExecutorService io = Executors.newSingleThreadExecutor();

    @Override public String id() { return "sports"; }

    @Override public ToolTag tag() { return ToolTag.SEARCH; }

    @Override
    public String description() {
        return "sports(team:str, type:\"last\"|\"next\") — "
                + "Dernier ou prochain match de foot (PSG, Lyon, équipe de France) "
                + "ou F1 (Ferrari, Red Bull, « formule 1 »). "
                + "type=\"last\" pour résultat récent, type=\"next\" pour prochain match. "
                + "Si l'API échoue, dis-le honnêtement et propose une recherche web.";
    }

    @Override
    public void execute(Context ctx, JSONObject params, ToolCallback cb) {
        String team = params.optString("team", "").trim();
        final String type = "next".equals(params.optString("type", "last").trim())
                ? "next" : "last";

        if (team.isEmpty()) {
            cb.onError("Précise l'équipe, l'écurie ou le sport.");
            return;
        }

        io.execute(() -> {
            try {
                String reply = null;

                if (ApiKeyStore.hasApiFootballKey(ctx)) {
                    try {
                        reply = ApiFootballService.fetch(ctx, team, type);
                    } catch (Exception ignored) {
                        reply = null;
                    }
                }

                if (reply == null || reply.isEmpty()) {
                    reply = fetchViaTavily(ctx, team, type);
                }

                if (reply == null || reply.isEmpty()) {
                    reply = fetchViaTheSportsDb(team, type);
                }

                if (reply == null || reply.isEmpty()) {
                    if (!ApiKeyStore.hasApiFootballKey(ctx)) {
                        cb.onSuccess(ToolResult.text("Je n'ai pas trouvé de résultat sportif. "
                                + "Ajoute une clé API-Football gratuite dans les réglages pour des scores à jour."));
                    } else {
                        cb.onSuccess(ToolResult.text("Je n'ai pas trouvé de résultat pour « " + team + " »."));
                    }
                    return;
                }
                cb.onSuccess(ToolResult.text(reply));
            } catch (Exception e) {
                cb.onError("Impossible de récupérer les résultats : " + e.getMessage());
            }
        });
    }

    private static String fetchViaTavily(Context ctx, String team, String type) {
        if (!ApiKeyStore.hasTavilyKey(ctx)) return null;
        try {
            String query = "next".equals(type)
                    ? "prochain match calendrier " + team + " foot ou F1"
                    : "dernier match score résultat " + team + " foot ou F1";
            TavilySearchService.Bundle bundle = TavilySearchService.search(ctx, query);
            if (!bundle.hasContent()) return null;
            return bundle.fallbackSpeech();
        } catch (Exception e) {
            return null;
        }
    }

    private static String fetchViaTheSportsDb(String team, String type) throws Exception {
        String searchName = resolveTeamQuery(team);

        if (isGenericF1Query(searchName)) {
            JSONObject ev = fetchF1LeagueEvent(type);
            if (ev == null) return null;
            return formatMotorsportEvent("Formule 1", type, ev);
        }

        JSONObject teamObj = null;
        boolean motorsport = isF1TeamQuery(searchName);

        if (motorsport) {
            teamObj = findF1Team(searchName);
        }
        if (teamObj == null) {
            teamObj = findTeamViaPlayer(searchName);
            if (teamObj != null) {
                motorsport = "formula 1".equalsIgnoreCase(
                        teamObj.optString("strLeague", ""));
            }
        }
        if (teamObj == null && motorsport) {
            teamObj = findF1Team(searchName);
        }
        if (teamObj == null) {
            teamObj = findFootballTeam(searchName);
            motorsport = false;
        }

        if (teamObj == null) return null;

        String teamId = teamObj.optString("idTeam", "");
        String teamName = teamObj.optString("strTeam", searchName);
        if (teamId.isEmpty()) return null;

        JSONArray events = fetchTeamEvents(teamId, type);
        if (events == null || events.length() == 0) return null;

        JSONObject ev = motorsport
                ? pickBestF1Event(events, type)
                : pickBestFootballEvent(events, type);
        return motorsport
                ? formatMotorsportEvent(teamName, type, ev)
                : formatFootballEvent(teamName, type, ev);
    }

    static String resolveTeamQuery(String team) {
        String key = team.trim().toLowerCase(Locale.ROOT);
        String alias = TEAM_ALIASES.get(key);
        return alias != null ? alias : team.trim();
    }

    static boolean isGenericF1Query(String name) {
        String t = name.toLowerCase(Locale.ROOT);
        return "f1".equals(t) || "formule 1".equals(t) || "formula 1".equals(t)
                || "formule1".equals(t) || "grand prix".equals(t);
    }

    static boolean isF1TeamQuery(String name) {
        String t = name.toLowerCase(Locale.ROOT);
        return t.contains("ferrari") || t.contains("red bull") || t.contains("mclaren")
                || t.contains("mercedes") || t.contains("alpine") || t.contains("aston martin")
                || t.contains("haas") || t.contains("racing bull") || t.contains("sauber")
                || t.contains("audi") || t.contains("cadillac") || t.contains("williams")
                || t.contains("f1") || t.contains("formule");
    }

    private static JSONObject fetchF1LeagueEvent(String type) throws Exception {
        String url = "next".equals(type)
                ? "https://www.thesportsdb.com/api/v1/json/3/eventsnextleague.php?id=" + F1_LEAGUE_ID
                : "https://www.thesportsdb.com/api/v1/json/3/eventspastleague.php?id=" + F1_LEAGUE_ID;
        JSONObject res = HttpJson.get(url);
        JSONArray events = res.optJSONArray("events");
        if (events == null || events.length() == 0) return null;
        return pickBestF1Event(events, type);
    }

    private static JSONObject findF1Team(String query) throws Exception {
        JSONObject res = HttpJson.get(
                "https://www.thesportsdb.com/api/v1/json/3/search_all_teams.php?l=Formula%201");
        JSONArray teams = res.optJSONArray("teams");
        if (teams == null || teams.length() == 0) return null;
        return pickBestTeam(teams, query, true);
    }

    private static JSONObject findFootballTeam(String searchName) throws Exception {
        String searchUrl = "https://www.thesportsdb.com/api/v1/json/3/searchteams.php"
                + "?t=" + URLEncoder.encode(searchName, "UTF-8");
        JSONObject searchRes = HttpJson.get(searchUrl);
        JSONArray teams = searchRes.optJSONArray("teams");
        if (teams == null || teams.length() == 0) return null;
        return pickBestTeam(teams, searchName, false);
    }

    private static JSONObject findTeamViaPlayer(String name) throws Exception {
        String url = "https://www.thesportsdb.com/api/v1/json/3/searchplayers.php"
                + "?p=" + URLEncoder.encode(name, "UTF-8");
        JSONObject res = HttpJson.get(url);
        JSONArray players = res.optJSONArray("player");
        if (players == null || players.length() == 0) return null;

        JSONObject player = players.getJSONObject(0);
        String teamId = player.optString("idTeam", "");
        String teamName = player.optString("strTeam", "");
        if (teamId.isEmpty()) return null;

        JSONObject team = new JSONObject();
        team.put("idTeam", teamId);
        team.put("strTeam", teamName.isEmpty() ? name : teamName);
        team.put("strLeague", player.optString("strSport", "").equals("Motorsport")
                ? "Formula 1" : player.optString("strTeam", ""));
        team.put("strSport", player.optString("strSport", ""));
        return team;
    }

    private static JSONArray fetchTeamEvents(String teamId, String type) throws Exception {
        String eventsUrl = "next".equals(type)
                ? "https://www.thesportsdb.com/api/v1/json/3/eventsnext.php?id=" + teamId
                : "https://www.thesportsdb.com/api/v1/json/3/eventslast.php?id=" + teamId;
        JSONObject eventsRes = HttpJson.get(eventsUrl);
        JSONArray events = eventsRes.optJSONArray("results");
        if (events == null || events.length() == 0) {
            events = eventsRes.optJSONArray("events");
        }
        return events;
    }

    static JSONObject pickBestTeam(JSONArray teams, String query, boolean preferMotorsport) {
        String q = query.toLowerCase(Locale.ROOT);
        JSONObject best = null;
        int bestScore = Integer.MIN_VALUE;
        for (int i = 0; i < teams.length(); i++) {
            JSONObject t = teams.optJSONObject(i);
            if (t == null) continue;
            int score = scoreTeam(t, q, preferMotorsport);
            if (score > bestScore) {
                bestScore = score;
                best = t;
            }
        }
        return best;
    }

    private static int scoreTeam(JSONObject t, String query, boolean preferMotorsport) {
        String sport = t.optString("strSport", "").toLowerCase(Locale.ROOT);
        String name = t.optString("strTeam", "").toLowerCase(Locale.ROOT);
        String alt = t.optString("strTeamAlternate", "").toLowerCase(Locale.ROOT);
        String league = t.optString("strLeague", "").toLowerCase(Locale.ROOT);

        int score = 0;
        if (preferMotorsport) {
            if ("motorsport".equals(sport) && league.contains("formula 1")) score += 150;
            if ("soccer".equals(sport)) score -= 100;
        } else {
            if ("soccer".equals(sport) || "football".equals(sport)) score += 120;
            if ("esports".equals(sport)) score -= 80;
            if ("motorsport".equals(sport)) score -= 40;
        }
        if (name.equals(query)) score += 100;
        if (name.replace("-", " ").contains(query) || query.contains(name)) score += 60;
        if (alt.contains(query)) score += 40;
        if (league.contains("ligue") || league.contains("champions")) score += 20;
        return score;
    }

    private static JSONObject pickBestF1Event(JSONArray events, String type) throws Exception {
        JSONObject fallback = events.getJSONObject(0);
        JSONObject best = null;
        int bestScore = Integer.MIN_VALUE;
        for (int i = 0; i < events.length(); i++) {
            JSONObject ev = events.getJSONObject(i);
            int score = scoreF1Event(ev, type);
            if (score > bestScore) {
                bestScore = score;
                best = ev;
            }
        }
        return best != null ? best : fallback;
    }

    private static int scoreF1Event(JSONObject ev, String type) {
        String eventName = ev.optString("strEvent", "").toLowerCase(Locale.ROOT);
        String date = ev.optString("dateEvent", "");
        int score = 0;
        if (eventName.contains("grand prix") && !eventName.contains("practice")
                && !eventName.contains("qualifying") && !eventName.contains("sprint")) {
            score += 80;
        }
        if ("next".equals(type) && eventName.contains("practice")) score += 10;
        if (!date.isEmpty()) {
            try {
                score += Integer.parseInt(date.substring(0, 4)) - 2000;
            } catch (Exception ignored) {}
        }
        return score;
    }

    private static JSONObject pickBestFootballEvent(JSONArray events, String type) throws Exception {
        if ("next".equals(type)) return events.getJSONObject(0);
        JSONObject best = events.getJSONObject(0);
        int bestScore = Integer.MIN_VALUE;
        for (int i = 0; i < events.length(); i++) {
            JSONObject ev = events.getJSONObject(i);
            int score = scorePastFootballEvent(ev);
            if (score > bestScore) {
                bestScore = score;
                best = ev;
            }
        }
        return best;
    }

    private static int scorePastFootballEvent(JSONObject ev) {
        String homeScore = ev.optString("intHomeScore", "").trim();
        String awayScore = ev.optString("intAwayScore", "").trim();
        String date = ev.optString("dateEvent", "");
        int score = 0;
        if (!homeScore.isEmpty() && !awayScore.isEmpty()) score += 50;
        if (!date.isEmpty()) {
            try {
                score += (Integer.parseInt(date.substring(0, 4)) - 2000) * 2;
            } catch (Exception ignored) {}
        }
        return score;
    }

    private static String formatMotorsportEvent(String teamName, String type, JSONObject ev) {
        String eventName = ev.optString("strEvent", "Épreuve");
        String date = ev.optString("dateEvent", "");
        String time = ev.optString("strTime", "");
        String venue = ev.optString("strVenue", "");
        String round = ev.optString("intRound", "");
        String result = ev.optString("strResult", "").trim();

        StringBuilder sb = new StringBuilder();
        if ("next".equals(type)) {
            sb.append("Prochaine épreuve");
            if (!"Formule 1".equals(teamName)) sb.append(" pour ").append(teamName);
            sb.append(" : ").append(eventName);
        } else {
            sb.append("Dernière épreuve");
            if (!"Formule 1".equals(teamName)) sb.append(" pour ").append(teamName);
            sb.append(" : ").append(eventName);
            if (!result.isEmpty()) sb.append(" — ").append(result);
        }
        if (!date.isEmpty()) sb.append(" le ").append(date);
        if (!time.isEmpty() && !"00:00:00".equals(time)) sb.append(" à ").append(time.substring(0, 5));
        if (!round.isEmpty() && !"0".equals(round)) sb.append(" (manche ").append(round).append(")");
        if (!venue.isEmpty()) sb.append(" — ").append(venue);
        sb.append(".");
        return sb.toString();
    }

    private static String formatFootballEvent(String teamName, String type, JSONObject ev) {
        String home = ev.optString("strHomeTeam", "?");
        String away = ev.optString("strAwayTeam", "?");
        String scoreH = ev.optString("intHomeScore", "");
        String scoreA = ev.optString("intAwayScore", "");
        String date = ev.optString("dateEvent", "");
        String league = ev.optString("strLeague", "");
        String status = ev.optString("strStatus", "");

        StringBuilder result = new StringBuilder();
        if ("next".equals(type)) {
            result.append("Prochain match de ").append(teamName).append(" : ")
                    .append(home).append(" vs ").append(away);
            if (!date.isEmpty()) result.append(" le ").append(date);
            if (!league.isEmpty()) result.append(" (").append(league).append(")");
        } else {
            result.append("Dernier match de ").append(teamName).append(" : ")
                    .append(home).append(" ").append(scoreH)
                    .append(" - ").append(scoreA).append(" ").append(away);
            if (!date.isEmpty()) result.append(" (").append(date).append(")");
            if (!league.isEmpty()) result.append(" — ").append(league);
            if (!status.isEmpty() && !"FT".equals(status)) {
                result.append(" [").append(status).append("]");
            }
        }
        result.append(".");
        return result.toString();
    }
}
