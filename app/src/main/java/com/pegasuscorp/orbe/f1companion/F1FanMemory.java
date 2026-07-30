package com.pegasuscorp.orbe.f1companion;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Mémoire fan F1 : notes, avis (takes), pronostics — faits séparés de la fiche GP.
 */
public final class F1FanMemory {

    public static final int MAX_NOTES = 40;
    public static final int MAX_TAKES = 60;
    public static final int MAX_PREDICTIONS = 40;

    public final List<String> notes = new ArrayList<>();
    public final List<Take> takes = new ArrayList<>();
    public final List<Prediction> predictions = new ArrayList<>();

    public static final class Take {
        public String text = "";
        public String gp = "";
        public int sessionKey;
        public long atMs;

        JSONObject toJson() throws Exception {
            return new JSONObject()
                    .put("text", text)
                    .put("gp", gp)
                    .put("sessionKey", sessionKey)
                    .put("atMs", atMs);
        }

        static Take fromJson(JSONObject o) {
            Take t = new Take();
            if (o == null) return t;
            t.text = o.optString("text", "");
            t.gp = o.optString("gp", "");
            t.sessionKey = o.optInt("sessionKey", 0);
            t.atMs = o.optLong("atMs", 0L);
            return t;
        }
    }

    public static final class Prediction {
        public String text = "";
        public String gp = "";
        public int sessionKey;
        public long atMs;
        public boolean resolved;
        /** null = en attente, true/false après résolution. */
        public Boolean correct;
        public String outcome = "";

        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject()
                    .put("text", text)
                    .put("gp", gp)
                    .put("sessionKey", sessionKey)
                    .put("atMs", atMs)
                    .put("resolved", resolved)
                    .put("outcome", outcome);
            if (correct != null) o.put("correct", correct.booleanValue());
            return o;
        }

        static Prediction fromJson(JSONObject o) {
            Prediction p = new Prediction();
            if (o == null) return p;
            p.text = o.optString("text", "");
            p.gp = o.optString("gp", "");
            p.sessionKey = o.optInt("sessionKey", 0);
            p.atMs = o.optLong("atMs", 0L);
            p.resolved = o.optBoolean("resolved", false);
            p.outcome = o.optString("outcome", "");
            if (o.has("correct") && !o.isNull("correct")) {
                p.correct = o.optBoolean("correct");
            }
            return p;
        }
    }

    public JSONObject toJson() throws Exception {
        JSONArray n = new JSONArray();
        for (String s : notes) n.put(s);
        JSONArray t = new JSONArray();
        for (Take take : takes) t.put(take.toJson());
        JSONArray p = new JSONArray();
        for (Prediction pred : predictions) p.put(pred.toJson());
        return new JSONObject()
                .put("notes", n)
                .put("takes", t)
                .put("predictions", p);
    }

    public static F1FanMemory fromJson(JSONObject o) {
        F1FanMemory m = new F1FanMemory();
        if (o == null) return m;
        JSONArray n = o.optJSONArray("notes");
        if (n != null) {
            for (int i = 0; i < n.length(); i++) {
                String s = n.optString(i, "").trim();
                if (!s.isEmpty()) m.notes.add(s);
            }
        }
        JSONArray t = o.optJSONArray("takes");
        if (t != null) {
            for (int i = 0; i < t.length(); i++) {
                Take take = Take.fromJson(t.optJSONObject(i));
                if (!take.text.isEmpty()) m.takes.add(take);
            }
        }
        JSONArray p = o.optJSONArray("predictions");
        if (p != null) {
            for (int i = 0; i < p.length(); i++) {
                Prediction pred = Prediction.fromJson(p.optJSONObject(i));
                if (!pred.text.isEmpty()) m.predictions.add(pred);
            }
        }
        return m;
    }

    public boolean isEmpty() {
        return notes.isEmpty() && takes.isEmpty() && predictions.isEmpty();
    }

    /** Section prompt / contexte nommé. */
    public String toMarkdown(List<FavoriteTeamsStore.TeamDef> favorites) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Mémoire fan F1\n\n");
        if (favorites != null && !favorites.isEmpty()) {
            sb.append("## Écuries suivies\n");
            for (FavoriteTeamsStore.TeamDef team : favorites) {
                sb.append("- ").append(team.label).append("\n");
            }
            sb.append("\n");
        }
        if (!notes.isEmpty()) {
            sb.append("## Préférences / notes\n");
            int from = Math.max(0, notes.size() - 12);
            for (int i = from; i < notes.size(); i++) {
                sb.append("- ").append(notes.get(i)).append("\n");
            }
            sb.append("\n");
        }
        if (!takes.isEmpty()) {
            sb.append("## Avis récents\n");
            int from = Math.max(0, takes.size() - 10);
            for (int i = takes.size() - 1; i >= from; i--) {
                Take t = takes.get(i);
                sb.append("- ");
                if (!t.gp.isEmpty()) sb.append("(").append(t.gp).append(") ");
                sb.append(t.text).append("\n");
            }
            sb.append("\n");
        }
        List<Prediction> open = new ArrayList<>();
        List<Prediction> done = new ArrayList<>();
        for (Prediction p : predictions) {
            if (p.resolved) done.add(p);
            else open.add(p);
        }
        if (!open.isEmpty()) {
            sb.append("## Pronostics en cours\n");
            for (int i = Math.max(0, open.size() - 8); i < open.size(); i++) {
                Prediction p = open.get(i);
                sb.append("- ");
                if (!p.gp.isEmpty()) sb.append("(").append(p.gp).append(") ");
                sb.append(p.text).append("\n");
            }
            sb.append("\n");
        }
        if (!done.isEmpty()) {
            sb.append("## Pronostics passés (chambrage)\n");
            int from = Math.max(0, done.size() - 8);
            for (int i = done.size() - 1; i >= from; i--) {
                Prediction p = done.get(i);
                String mark = p.correct == null ? "?" : (p.correct ? "✓" : "✗");
                sb.append("- [").append(mark).append("] ");
                if (!p.gp.isEmpty()) sb.append("(").append(p.gp).append(") ");
                sb.append(p.text);
                if (!p.outcome.isEmpty()) sb.append(" → ").append(p.outcome);
                sb.append("\n");
            }
            sb.append("\n");
        }
        sb.append("Utilise cette mémoire pour chambrer gentiment, rappeler un avis ou un pronostic. "
                + "Ne l'invente pas.\n");
        return sb.toString().trim();
    }

    public String summaryLine() {
        int open = 0;
        for (Prediction p : predictions) if (!p.resolved) open++;
        return takes.size() + " avis · " + open + " pronostic(s) · " + notes.size() + " note(s)";
    }
}
