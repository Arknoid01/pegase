package com.pegasuscorp.orbe.f1companion;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Fiche structurée d'un week-end F1 — faits uniquement (pas d'interprétation LLM).
 */
public final class WeekendSnapshot {

    public String event = "";
    public String date = "";
    public String circuit = "";
    public String country = "";
    public int year;
    public int meetingKey;
    public int sessionKey;
    public final List<ResultRow> results = new ArrayList<>();
    public final List<GridRow> qualifying = new ArrayList<>();
    public final List<String> incidents = new ArrayList<>();
    public final List<String> strategies = new ArrayList<>();
    public final List<String> keyFacts = new ArrayList<>();
    public final List<String> sources = new ArrayList<>();
    public long updatedAtMs;

    public static final class ResultRow {
        public int position;
        public String driver = "";
        public String team = "";
        public int driverNumber;
        public boolean dnf;
        public boolean dns;
        public boolean dsq;
        public double points;
        public String gap = "";
        public int laps;

        JSONObject toJson() throws Exception {
            return new JSONObject()
                    .put("position", position)
                    .put("driver", driver)
                    .put("team", team)
                    .put("driver_number", driverNumber)
                    .put("dnf", dnf)
                    .put("dns", dns)
                    .put("dsq", dsq)
                    .put("points", points)
                    .put("gap", gap)
                    .put("laps", laps);
        }

        static ResultRow fromJson(JSONObject o) {
            ResultRow r = new ResultRow();
            if (o == null) return r;
            r.position = o.optInt("position", 0);
            r.driver = o.optString("driver", "");
            r.team = o.optString("team", "");
            r.driverNumber = o.optInt("driver_number", 0);
            r.dnf = o.optBoolean("dnf", false);
            r.dns = o.optBoolean("dns", false);
            r.dsq = o.optBoolean("dsq", false);
            r.points = o.optDouble("points", 0);
            r.gap = o.optString("gap", "");
            r.laps = o.optInt("laps", 0);
            return r;
        }
    }

    public static final class GridRow {
        public int position;
        public String driver = "";
        public String team = "";
        public int driverNumber;

        JSONObject toJson() throws Exception {
            return new JSONObject()
                    .put("position", position)
                    .put("driver", driver)
                    .put("team", team)
                    .put("driver_number", driverNumber);
        }

        static GridRow fromJson(JSONObject o) {
            GridRow g = new GridRow();
            if (o == null) return g;
            g.position = o.optInt("position", 0);
            g.driver = o.optString("driver", "");
            g.team = o.optString("team", "");
            g.driverNumber = o.optInt("driver_number", 0);
            return g;
        }
    }

    public boolean hasRaceResults() {
        return !results.isEmpty();
    }

    public String podiumLine() {
        if (results.size() < 3) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(3, results.size()); i++) {
            ResultRow r = results.get(i);
            if (i > 0) sb.append(", ");
            sb.append(r.driver.isEmpty() ? "#" + r.driverNumber : r.driver);
        }
        return sb.toString();
    }

    public JSONObject toJson() throws Exception {
        JSONObject o = new JSONObject();
        o.put("event", event);
        o.put("date", date);
        o.put("circuit", circuit);
        o.put("country", country);
        o.put("year", year);
        o.put("meeting_key", meetingKey);
        o.put("session_key", sessionKey);
        o.put("updated_at_ms", updatedAtMs);
        JSONArray res = new JSONArray();
        for (ResultRow r : results) res.put(r.toJson());
        o.put("results", res);
        JSONArray grid = new JSONArray();
        for (GridRow g : qualifying) grid.put(g.toJson());
        o.put("qualifying", grid);
        o.put("incidents", new JSONArray(incidents));
        o.put("strategies", new JSONArray(strategies));
        o.put("key_facts", new JSONArray(keyFacts));
        o.put("sources", new JSONArray(sources));
        return o;
    }

    public static WeekendSnapshot fromJson(JSONObject o) {
        WeekendSnapshot s = new WeekendSnapshot();
        if (o == null) return s;
        s.event = o.optString("event", "");
        s.date = o.optString("date", "");
        s.circuit = o.optString("circuit", "");
        s.country = o.optString("country", "");
        s.year = o.optInt("year", 0);
        s.meetingKey = o.optInt("meeting_key", 0);
        s.sessionKey = o.optInt("session_key", 0);
        s.updatedAtMs = o.optLong("updated_at_ms", 0L);
        JSONArray res = o.optJSONArray("results");
        if (res != null) {
            for (int i = 0; i < res.length(); i++) {
                s.results.add(ResultRow.fromJson(res.optJSONObject(i)));
            }
        }
        JSONArray grid = o.optJSONArray("qualifying");
        if (grid != null) {
            for (int i = 0; i < grid.length(); i++) {
                s.qualifying.add(GridRow.fromJson(grid.optJSONObject(i)));
            }
        }
        appendStrings(o.optJSONArray("incidents"), s.incidents);
        appendStrings(o.optJSONArray("strategies"), s.strategies);
        appendStrings(o.optJSONArray("key_facts"), s.keyFacts);
        appendStrings(o.optJSONArray("sources"), s.sources);
        return s;
    }

    private static void appendStrings(JSONArray arr, List<String> out) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            String t = arr.optString(i, "").trim();
            if (!t.isEmpty()) out.add(t);
        }
    }

    /** Markdown injecté dans le contexte nommé « f1 ». */
    public String toMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Fiche Grand Prix — ").append(nz(event, "Week-end F1")).append("\n\n");
        sb.append("- Date : ").append(nz(date, "?")).append("\n");
        sb.append("- Circuit : ").append(nz(circuit, "?"));
        if (!country.isEmpty()) sb.append(" (").append(country).append(")");
        sb.append("\n\n");

        sb.append("## FAITS (sources OpenF1 — ne pas inventer)\n\n");
        if (!results.isEmpty()) {
            sb.append("### Résultat final\n");
            for (ResultRow r : results) {
                sb.append(r.position).append(". ")
                        .append(nz(r.driver, "#" + r.driverNumber));
                if (!r.team.isEmpty()) sb.append(" (").append(r.team).append(")");
                if (r.dnf) sb.append(" — DNF");
                if (r.dns) sb.append(" — DNS");
                if (r.dsq) sb.append(" — DSQ");
                if (!r.gap.isEmpty() && r.position > 1) sb.append(" · écart ").append(r.gap);
                if (r.points > 0) sb.append(" · ").append(trimPoints(r.points)).append(" pts");
                sb.append("\n");
            }
            sb.append("\n");
        }
        if (!qualifying.isEmpty()) {
            sb.append("### Grille de départ\n");
            for (GridRow g : qualifying) {
                sb.append("P").append(g.position).append(" ")
                        .append(nz(g.driver, "#" + g.driverNumber));
                if (!g.team.isEmpty()) sb.append(" (").append(g.team).append(")");
                sb.append("\n");
            }
            sb.append("\n");
            sb.append("### Positions gagnées / perdues\n");
            for (String line : positionDeltas()) {
                sb.append("- ").append(line).append("\n");
            }
            sb.append("\n");
        }
        if (!incidents.isEmpty()) {
            sb.append("### Incidents / drapeaux / Race Control\n");
            for (String i : incidents) sb.append("- ").append(i).append("\n");
            sb.append("\n");
        }
        if (!strategies.isEmpty()) {
            sb.append("### Arrêts / stints (aperçu)\n");
            for (String s : strategies) sb.append("- ").append(s).append("\n");
            sb.append("\n");
        }
        if (!keyFacts.isEmpty()) {
            sb.append("### Faits marquants (dérivés des données)\n");
            for (String f : keyFacts) sb.append("- ").append(f).append("\n");
            sb.append("\n");
        }
        sb.append("## Règles pour Pégase\n");
        sb.append("- Les lignes FAITS sont des données structurées — ne les contredis pas.\n");
        sb.append("- Ton analyse / opinion doit être présentée comme une interprétation "
                + "(« Je pense que… », « À mon avis… »).\n");
        sb.append("- Ne refais pas de recherche web pour ce GP tant que cette fiche est chargée, "
                + "sauf si l'utilisateur demande une actu hors fiche.\n");
        if (!sources.isEmpty()) {
            sb.append("\nSources : ").append(String.join(", ", sources)).append("\n");
        }
        return sb.toString();
    }

    List<String> positionDeltas() {
        if (results.isEmpty() || qualifying.isEmpty()) return Collections.emptyList();
        java.util.Map<Integer, Integer> gridPos = new java.util.HashMap<>();
        for (GridRow g : qualifying) {
            if (g.driverNumber > 0) gridPos.put(g.driverNumber, g.position);
        }
        List<String> out = new ArrayList<>();
        for (ResultRow r : results) {
            if (r.driverNumber <= 0 || !gridPos.containsKey(r.driverNumber)) continue;
            int start = gridPos.get(r.driverNumber);
            int delta = start - r.position;
            if (delta == 0) continue;
            String name = nz(r.driver, "#" + r.driverNumber);
            if (delta > 0) {
                out.add(name + " : P" + start + " → P" + r.position
                        + " (+" + delta + " places)");
            } else {
                out.add(name + " : P" + start + " → P" + r.position
                        + " (" + delta + " places)");
            }
        }
        return out;
    }

    private static String nz(String s, String fallback) {
        return s == null || s.trim().isEmpty() ? fallback : s.trim();
    }

    private static String trimPoints(double p) {
        if (Math.abs(p - Math.rint(p)) < 0.01) return String.valueOf((int) Math.rint(p));
        return String.valueOf(p);
    }
}
