package com.pegasuscorp.orbe.tools.device;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Registre local des alarmes/minuteurs créés par Pégase (Android ne permet pas de les lister).
 */
public final class UtilityScheduleStore {

    public static final class AlarmRecord {
        public final int hour;
        public final int minute;
        public final String label;
        public final String createdAt;

        AlarmRecord(int hour, int minute, String label, String createdAt) {
            this.hour = hour;
            this.minute = minute;
            this.label = label != null ? label : "";
            this.createdAt = createdAt != null ? createdAt : "";
        }
    }

    public static final class TimerRecord {
        public final int seconds;
        public final String label;
        public final long startedAtMs;

        TimerRecord(int seconds, String label, long startedAtMs) {
            this.seconds = seconds;
            this.label = label != null ? label : "";
            this.startedAtMs = startedAtMs;
        }
    }

    private static final int MAX_ITEMS = 24;

    private final File file;
    private final List<AlarmRecord> alarms = new ArrayList<>();
    private final List<TimerRecord> timers = new ArrayList<>();

    private UtilityScheduleStore(Context context) {
        File dir = new File(context.getApplicationContext().getFilesDir(), "utility");
        if (!dir.exists()) dir.mkdirs();
        file = new File(dir, "schedule.json");
        load();
    }

    public static UtilityScheduleStore get(Context context) {
        return new UtilityScheduleStore(context);
    }

    public synchronized void recordAlarm(int hour, int minute, String label) {
        alarms.add(0, new AlarmRecord(hour, minute, label, today()));
        trim(alarms);
        save();
    }

    public synchronized void recordTimer(int seconds, String label, long startedAtMs) {
        timers.add(0, new TimerRecord(seconds, label, startedAtMs));
        trim(timers);
        save();
    }

    public synchronized List<AlarmRecord> recentAlarms() {
        return Collections.unmodifiableList(new ArrayList<>(alarms));
    }

    public synchronized List<TimerRecord> recentTimers() {
        return Collections.unmodifiableList(new ArrayList<>(timers));
    }

    public synchronized String formatRecentAlarms() {
        if (alarms.isEmpty()) return "Aucune alarme enregistrée par Pégase récemment.";
        StringBuilder sb = new StringBuilder("Alarmes récentes (Pégase) :\n");
        for (int i = 0; i < Math.min(8, alarms.size()); i++) {
            AlarmRecord a = alarms.get(i);
            sb.append("• ").append(String.format(Locale.ROOT, "%02dh%02d", a.hour, a.minute));
            if (!a.label.isEmpty()) sb.append(" — ").append(a.label);
            if (!a.createdAt.isEmpty()) sb.append(" (").append(a.createdAt).append(")");
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    public synchronized String formatRecentTimers() {
        if (timers.isEmpty()) return "Aucun minuteur enregistré par Pégase récemment.";
        StringBuilder sb = new StringBuilder("Minuteurs récents (Pégase) :\n");
        long now = System.currentTimeMillis();
        for (int i = 0; i < Math.min(8, timers.size()); i++) {
            TimerRecord t = timers.get(i);
            long elapsed = Math.max(0, (now - t.startedAtMs) / 1000L);
            long remaining = Math.max(0, t.seconds - elapsed);
            sb.append("• ").append(TimerTool.formatLabel(t.seconds));
            if (!t.label.isEmpty()) sb.append(" — ").append(t.label);
            if (remaining > 0 && elapsed < t.seconds + 120) {
                sb.append(" (~").append(TimerTool.formatLabel((int) remaining)).append(" restantes)");
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    static void resetForTests() {
        // no singleton — tests use temp context files
    }

    private static void trim(List<?> list) {
        while (list.size() > MAX_ITEMS) list.remove(list.size() - 1);
    }

    private static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());
    }

    private void load() {
        alarms.clear();
        timers.clear();
        if (!file.exists()) return;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            JSONObject root = new JSONObject(sb.toString());
            JSONArray arrA = root.optJSONArray("alarms");
            if (arrA != null) {
                for (int i = 0; i < arrA.length(); i++) {
                    JSONObject o = arrA.optJSONObject(i);
                    if (o == null) continue;
                    alarms.add(new AlarmRecord(
                            o.optInt("hour", 0),
                            o.optInt("minute", 0),
                            o.optString("label", ""),
                            o.optString("createdAt", "")));
                }
            }
            JSONArray arrT = root.optJSONArray("timers");
            if (arrT != null) {
                for (int i = 0; i < arrT.length(); i++) {
                    JSONObject o = arrT.optJSONObject(i);
                    if (o == null) continue;
                    timers.add(new TimerRecord(
                            o.optInt("seconds", 0),
                            o.optString("label", ""),
                            o.optLong("startedAt", System.currentTimeMillis())));
                }
            }
        } catch (Exception ignored) {}
    }

    private void save() {
        try {
            JSONObject root = new JSONObject();
            JSONArray arrA = new JSONArray();
            for (AlarmRecord a : alarms) {
                arrA.put(new JSONObject()
                        .put("hour", a.hour)
                        .put("minute", a.minute)
                        .put("label", a.label)
                        .put("createdAt", a.createdAt));
            }
            JSONArray arrT = new JSONArray();
            for (TimerRecord t : timers) {
                arrT.put(new JSONObject()
                        .put("seconds", t.seconds)
                        .put("label", t.label)
                        .put("startedAt", t.startedAtMs));
            }
            root.put("alarms", arrA);
            root.put("timers", arrT);
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {}
    }
}
