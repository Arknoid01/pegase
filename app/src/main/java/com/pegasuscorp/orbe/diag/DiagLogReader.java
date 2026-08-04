package com.pegasuscorp.orbe.diag;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Lecture légère des JSONL diag (tail) — pas d'écriture, pour le dashboard debug.
 */
public final class DiagLogReader {

    private DiagLogReader() {}

    /** Dernières {@code maxLines} lignes non vides (ordre chronologique). */
    public static List<String> tailLines(File file, int maxLines) {
        ArrayList<String> out = new ArrayList<>();
        if (file == null || !file.isFile() || maxLines <= 0) return out;
        ArrayDeque<String> ring = new ArrayDeque<>(Math.min(maxLines, 512));
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isEmpty()) continue;
                if (ring.size() >= maxLines) ring.removeFirst();
                ring.addLast(line);
            }
        } catch (Exception ignored) {
            return out;
        }
        out.addAll(ring);
        return out;
    }

    public static List<JSONObject> tailJson(File file, int maxLines) {
        List<String> lines = tailLines(file, maxLines);
        ArrayList<JSONObject> out = new ArrayList<>(lines.size());
        for (String line : lines) {
            try {
                out.add(new JSONObject(line));
            } catch (Exception ignored) {}
        }
        return out;
    }

    /** Parse {@code ts} ISO-8601 → epoch ms, ou 0. */
    public static long parseTsMs(JSONObject o) {
        if (o == null) return 0L;
        if (o.has("ts_ms")) {
            long v = o.optLong("ts_ms", 0L);
            if (v > 0L) return v;
        }
        return parseTsMs(o.optString("ts", o.optString("t", "")));
    }

    public static long parseTsMs(String ts) {
        if (ts == null || ts.isEmpty()) return 0L;
        try {
            return Instant.parse(ts).toEpochMilli();
        } catch (DateTimeParseException ignored) {}
        // Variantes sans Z / millis
        try {
            String s = ts.trim();
            if (s.length() >= 19 && s.charAt(10) == ' ') {
                s = s.substring(0, 10) + "T" + s.substring(11);
            }
            if (!s.endsWith("Z") && s.indexOf('+') < 0 && s.length() >= 19) {
                s = s + "Z";
            }
            return Instant.parse(s).toEpochMilli();
        } catch (Exception ignored) {
            return 0L;
        }
    }

    public static List<JSONObject> filterSince(List<JSONObject> all, long sinceMs) {
        if (all == null || all.isEmpty()) return Collections.emptyList();
        if (sinceMs <= 0L) return new ArrayList<>(all);
        ArrayList<JSONObject> out = new ArrayList<>();
        for (JSONObject o : all) {
            long t = parseTsMs(o);
            // Sans ts : on garde (évite de tout perdre sur vieux logs)
            if (t <= 0L || t >= sinceMs) out.add(o);
        }
        return out;
    }

    public static void sortByTs(List<JSONObject> list, boolean newestFirst) {
        if (list == null || list.size() < 2) return;
        Comparator<JSONObject> cmp = (a, b) -> Long.compare(parseTsMs(a), parseTsMs(b));
        list.sort(newestFirst ? cmp.reversed() : cmp);
    }

    public static boolean matches(JSONObject o, String... eventOrTypes) {
        if (o == null || eventOrTypes == null) return false;
        String event = o.optString("event", "");
        String type = o.optString("type", "");
        for (String want : eventOrTypes) {
            if (want == null || want.isEmpty()) continue;
            if (want.equals(event) || want.equals(type)) return true;
            if (event.contains(want) || type.contains(want)) return true;
        }
        return false;
    }

    /** Dernier objet dont {@code event} (ou {@code type}) matche. */
    public static JSONObject lastMatching(File file, int scanLines, String... eventOrTypes) {
        List<JSONObject> all = tailJson(file, scanLines);
        for (int i = all.size() - 1; i >= 0; i--) {
            if (matches(all.get(i), eventOrTypes)) return all.get(i);
        }
        return null;
    }

    public static JSONObject lastMatching(List<JSONObject> all, String... eventOrTypes) {
        if (all == null) return null;
        for (int i = all.size() - 1; i >= 0; i--) {
            if (matches(all.get(i), eventOrTypes)) return all.get(i);
        }
        return null;
    }

    public static List<JSONObject> lastMatchingMany(
            File file, int scanLines, int maxHits, String... eventOrTypes) {
        return lastMatchingMany(tailJson(file, scanLines), maxHits, eventOrTypes);
    }

    public static List<JSONObject> lastMatchingMany(
            List<JSONObject> all, int maxHits, String... eventOrTypes) {
        ArrayList<JSONObject> hits = new ArrayList<>();
        if (all == null) return hits;
        for (int i = all.size() - 1; i >= 0 && hits.size() < maxHits; i--) {
            if (matches(all.get(i), eventOrTypes)) hits.add(all.get(i));
        }
        return hits;
    }

    public static int countMatching(List<JSONObject> all, String... eventOrTypes) {
        int n = 0;
        if (all == null) return 0;
        for (JSONObject o : all) {
            if (matches(o, eventOrTypes)) n++;
        }
        return n;
    }
}
