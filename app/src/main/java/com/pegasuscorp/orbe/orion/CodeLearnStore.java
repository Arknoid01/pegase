package com.pegasuscorp.orbe.orion;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Préférences code confirmées + candidats d'apprentissage (jamais vérité silencieuse).
 */
public final class CodeLearnStore {

    private static final String PREFS = "orion_code_learn";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_CONFIRMED = "confirmed_json";
    private static final String KEY_CANDIDATES = "candidates_json";
    private static final String KEY_ENTRIES = "entries_json"; // legacy Q/R
    private static final int MAX = 40;

    public static final class Candidate {
        public final String id;
        public final String summary;
        public final String phrase;
        public final long atMs;

        Candidate(String id, String summary, String phrase, long atMs) {
            this.id = id;
            this.summary = summary;
            this.phrase = phrase;
            this.atMs = atMs;
        }
    }

    private CodeLearnStore() {}

    public static boolean isEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_ENABLED, true);
    }

    public static void setEnabled(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, on).apply();
    }

    /** Préférences confirmées pour le méta-prompt. */
    public static String relevantHint(Context ctx, String demand) {
        if (ctx == null || !isEnabled(ctx)) return "";
        StringBuilder sb = new StringBuilder();
        try {
            JSONArray conf = loadArr(ctx, KEY_CONFIRMED);
            String dFold = fold(demand);
            int n = 0;
            for (int i = conf.length() - 1; i >= 0 && n < 5; i--) {
                JSONObject o = conf.optJSONObject(i);
                if (o == null) continue;
                String summary = o.optString("summary", "");
                String phrase = o.optString("phrase", "");
                if (summary.isEmpty()) continue;
                if (!TextUtils.isEmpty(dFold) && !TextUtils.isEmpty(phrase)
                        && !overlaps(dFold, fold(phrase)) && !sharesToken(dFold, fold(phrase))) {
                    // garder quand même les prefs générales courtes
                    if (summary.length() > 120) continue;
                }
                sb.append("- ").append(summary.trim()).append('\n');
                n++;
            }
            // legacy Q/R
            JSONArray legacy = loadArr(ctx, KEY_ENTRIES);
            for (int i = legacy.length() - 1; i >= 0 && n < 6; i--) {
                JSONObject o = legacy.optJSONObject(i);
                if (o == null) continue;
                String qa = o.optString("qa", "");
                String phrase = o.optString("phrase", "");
                if (qa.isEmpty()) continue;
                if (!overlaps(dFold, fold(phrase)) && !sharesToken(dFold, fold(phrase))) continue;
                sb.append("- « ").append(clip(phrase, 40)).append(" » → ")
                        .append(clip(qa, 100)).append('\n');
                n++;
            }
        } catch (Exception ignored) {
        }
        return sb.toString().trim();
    }

    /** Propose un candidat — à confirmer explicitement. */
    public static void proposeCandidate(Context ctx, String phrase, String summary) {
        if (ctx == null || TextUtils.isEmpty(summary)) return;
        try {
            JSONArray arr = loadArr(ctx, KEY_CANDIDATES);
            String id = "c" + System.currentTimeMillis();
            arr.put(new JSONObject()
                    .put("id", id)
                    .put("phrase", phrase == null ? "" : phrase.trim())
                    .put("summary", summary.trim())
                    .put("at", System.currentTimeMillis()));
            while (arr.length() > MAX) arr = dropFirst(arr);
            prefs(ctx).edit().putString(KEY_CANDIDATES, arr.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    public static Candidate peekLatestCandidate(Context ctx) {
        try {
            JSONArray arr = loadArr(ctx, KEY_CANDIDATES);
            if (arr.length() == 0) return null;
            JSONObject o = arr.optJSONObject(arr.length() - 1);
            if (o == null) return null;
            return new Candidate(o.optString("id"), o.optString("summary"),
                    o.optString("phrase"), o.optLong("at"));
        } catch (Exception e) {
            return null;
        }
    }

    public static void acceptCandidate(Context ctx, String id) {
        if (ctx == null || TextUtils.isEmpty(id)) return;
        try {
            JSONArray cand = loadArr(ctx, KEY_CANDIDATES);
            JSONObject found = null;
            JSONArray rest = new JSONArray();
            for (int i = 0; i < cand.length(); i++) {
                JSONObject o = cand.optJSONObject(i);
                if (o == null) continue;
                if (id.equals(o.optString("id"))) found = o;
                else rest.put(o);
            }
            prefs(ctx).edit().putString(KEY_CANDIDATES, rest.toString()).apply();
            if (found == null) return;
            JSONArray conf = loadArr(ctx, KEY_CONFIRMED);
            conf.put(new JSONObject()
                    .put("phrase", found.optString("phrase"))
                    .put("summary", found.optString("summary"))
                    .put("at", System.currentTimeMillis()));
            while (conf.length() > MAX) conf = dropFirst(conf);
            prefs(ctx).edit().putString(KEY_CONFIRMED, conf.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    public static void refuseCandidate(Context ctx, String id) {
        if (ctx == null || TextUtils.isEmpty(id)) return;
        try {
            JSONArray cand = loadArr(ctx, KEY_CANDIDATES);
            JSONArray rest = new JSONArray();
            for (int i = 0; i < cand.length(); i++) {
                JSONObject o = cand.optJSONObject(i);
                if (o == null) continue;
                if (id.equals(o.optString("id"))) continue;
                rest.put(o);
            }
            prefs(ctx).edit().putString(KEY_CANDIDATES, rest.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    /** Legacy : mémoriser Q/R de session (toujours après clarification). */
    public static void remember(Context ctx, String phrase, String clarifications) {
        if (ctx == null || TextUtils.isEmpty(phrase) || TextUtils.isEmpty(clarifications)) return;
        try {
            JSONArray arr = loadArr(ctx, KEY_ENTRIES);
            String f = fold(phrase);
            JSONArray next = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                if (f.equals(o.optString("fold"))) continue;
                next.put(o);
            }
            next.put(new JSONObject()
                    .put("phrase", phrase.trim())
                    .put("fold", f)
                    .put("qa", clarifications.trim())
                    .put("at", System.currentTimeMillis()));
            while (next.length() > MAX) next = dropFirst(next);
            prefs(ctx).edit().putString(KEY_ENTRIES, next.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    static String fold(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT)
                .replace('é', 'e').replace('è', 'e').replace('ê', 'e')
                .replace('à', 'a').replace('â', 'a')
                .replace('ô', 'o').replace('ù', 'u')
                .replace('î', 'i').replace('ç', 'c')
                .replaceAll("[^a-z0-9]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static boolean overlaps(String a, String b) {
        return !a.isEmpty() && !b.isEmpty() && (a.contains(b) || b.contains(a));
    }

    private static boolean sharesToken(String a, String b) {
        String[] ta = a.split(" ");
        String[] tb = b.split(" ");
        int hits = 0;
        for (String x : ta) {
            if (x.length() < 4) continue;
            for (String y : tb) if (x.equals(y)) hits++;
        }
        return hits >= 2;
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static JSONArray dropFirst(JSONArray arr) throws Exception {
        JSONArray n = new JSONArray();
        for (int i = 1; i < arr.length(); i++) n.put(arr.get(i));
        return n;
    }

    private static JSONArray loadArr(Context ctx, String key) {
        try {
            String raw = prefs(ctx).getString(key, "[]");
            return new JSONArray(raw == null || raw.isEmpty() ? "[]" : raw);
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
