package com.pegasuscorp.orbe.diag;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** IO traces / rapports diag (jsonl + orbe-diag-report.json). */
public final class DiagParser {

    private DiagParser() {}

    static JSONObject ensureReport(Context ctx) {
        try {
            if (ctx != null) {
                Trace.init(ctx);
                DiagReport.generate(ctx);
            }
        } catch (Exception ignored) {}
        return loadReportQuiet();
    }

    static JSONObject loadReportQuiet() {
        try {
            File f = Trace.file();
            if (f == null) return null;
            File report = new File(f.getParentFile(), "orbe-diag-report.json");
            if (!report.exists()) return null;
            String raw = new String(java.nio.file.Files.readAllBytes(report.toPath()),
                    StandardCharsets.UTF_8);
            return new JSONObject(raw);
        } catch (Exception e) {
            return null;
        }
    }

    static List<JSONObject> readTraceEvents() {
        return readEvents(Trace.file());
    }

    /**
     * Charge les archives des N derniers jours + la trace du jour (si non vide).
     * Ordre : plus récent → plus ancien.
     */
    static List<DayBucket> loadArchiveDays(int daysBack) {
        List<DayBucket> out = new ArrayList<>();
        java.time.LocalDate today = java.time.LocalDate.now();
        // Aujourd'hui = trace courante
        List<JSONObject> todayEvents = withoutStress(readTraceEvents());
        if (!todayEvents.isEmpty()) {
            out.add(new DayBucket(today.toString() + " (aujourd'hui)", todayEvents));
        }
        // Jours passés = archives
        for (int i = 1; i <= daysBack; i++) {
            java.time.LocalDate d = today.minusDays(i);
            File f = Trace.archiveFile(d);
            List<JSONObject> ev = withoutStress(readEvents(f));
            if (!ev.isEmpty()) {
                out.add(new DayBucket(d.toString(), ev));
            }
        }
        return out;
    }

    static List<JSONObject> readEvents(File f) {
        List<JSONObject> out = new ArrayList<>();
        if (f == null || !f.exists()) return out;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                try {
                    out.add(new JSONObject(line));
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return out;
    }

    static List<JSONObject> withoutStress(List<JSONObject> events) {
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject e : events) {
            if (!e.optBoolean("stress", false)) out.add(e);
        }
        return out;
    }

    /** Bucket jour (archives / weekly) — accessible pour {@link DiagBehaviorIndex}. */
    public static final class DayBucket {
        public final String label;
        public final List<JSONObject> events;

        public DayBucket(String label, List<JSONObject> events) {
            this.label = label;
            this.events = events;
        }
    }
}
