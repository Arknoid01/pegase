package com.pegasuscorp.orbe.diag;

import android.content.Context;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Agrégat journalier des traces — survit à {@link Trace#clear(Context)}.
 * Fichier courant {@code day-aggregate.json} (aujourd'hui) ;
 * au changement de date, snapshot {@code day-aggregate-YYYY-MM-DD.json}
 * (même schéma JSON — champs existants inchangés ; {@code error_details} ajouté).
 */
public final class DiagDayAggregate {

    private static final String FILE_NAME = "day-aggregate.json";
    /** Bornes la liste détaillée — le compteur {@link #errors} reste exact. */
    public static final int MAX_ERROR_DETAILS = 20;
    private static final DateTimeFormatter HM =
            DateTimeFormatter.ofPattern("H'h'mm", Locale.FRENCH);
    private static final DateTimeFormatter DD_MM =
            DateTimeFormatter.ofPattern("dd/MM", Locale.FRENCH);

    public final String date;
    public final int events;
    public final int messages;
    public final int toolFails;
    public final int errors;
    public final long firstTs;
    public final long lastTs;
    /**
     * Dernières erreurs / échecs outil (borné). Compteurs {@link #errors} /
     * {@link #toolFails} restent le total exact même si la liste est tronquée.
     */
    public final List<JSONObject> errorDetails;

    public DiagDayAggregate(String date, int events, int messages, int toolFails,
            int errors, long firstTs, long lastTs) {
        this(date, events, messages, toolFails, errors, firstTs, lastTs, null);
    }

    public DiagDayAggregate(String date, int events, int messages, int toolFails,
            int errors, long firstTs, long lastTs, List<JSONObject> errorDetails) {
        this.date = date == null ? "" : date;
        this.events = Math.max(0, events);
        this.messages = Math.max(0, messages);
        this.toolFails = Math.max(0, toolFails);
        this.errors = Math.max(0, errors);
        this.firstTs = firstTs;
        this.lastTs = lastTs;
        if (errorDetails == null || errorDetails.isEmpty()) {
            this.errorDetails = Collections.emptyList();
        } else {
            List<JSONObject> copy = new ArrayList<>(errorDetails.size());
            for (JSONObject o : errorDetails) {
                if (o == null) continue;
                try {
                    copy.add(new JSONObject(o.toString()));
                } catch (Exception ignored) {
                }
            }
            this.errorDetails = Collections.unmodifiableList(copy);
        }
    }

    public boolean isEmpty() {
        return events <= 0 && messages <= 0;
    }

    public boolean hasIssues() {
        return toolFails > 0 || errors > 0;
    }

    /** Total erreurs + échecs outil (pour messages « N erreurs enregistrées »). */
    public int issueCount() {
        return errors + toolFails;
    }

    /** Snapshot disque pour la date civile courante (fichier absent → vide). */
    public static DiagDayAggregate load() {
        return load(LocalDate.now());
    }

    /**
     * Charge l'agrégat de {@code day} : fichier courant si aujourd'hui,
     * sinon snapshot daté (même schéma).
     */
    public static DiagDayAggregate load(LocalDate day) {
        if (day == null) return empty(LocalDate.now());
        LocalDate today = LocalDate.now();
        if (day.equals(today)) {
            return loadFromFile(file(), day);
        }
        return loadFromFile(historyFile(day), day);
    }

    private static DiagDayAggregate loadFromFile(File f, LocalDate expectedDay) {
        if (f == null || !f.exists() || expectedDay == null) {
            return empty(expectedDay);
        }
        try {
            String raw = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                    StandardCharsets.UTF_8);
            JSONObject o = new JSONObject(raw);
            String date = o.optString("date", "");
            if (!expectedDay.toString().equals(date)) {
                return empty(expectedDay);
            }
            return fromJson(o);
        } catch (Exception e) {
            return empty(expectedDay);
        }
    }

    static DiagDayAggregate fromJson(JSONObject o) {
        if (o == null) return empty(LocalDate.now());
        return new DiagDayAggregate(
                o.optString("date", ""),
                o.optInt("events", 0),
                o.optInt("messages", 0),
                o.optInt("tool_fails", 0),
                o.optInt("errors", 0),
                o.optLong("first_ts", 0L),
                o.optLong("last_ts", 0L),
                parseErrorDetails(o.optJSONArray("error_details")));
    }

    private static List<JSONObject> parseErrorDetails(JSONArray arr) {
        if (arr == null || arr.length() == 0) return Collections.emptyList();
        List<JSONObject> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject item = arr.optJSONObject(i);
            if (item != null) out.add(item);
        }
        return out;
    }

    static DiagDayAggregate empty(LocalDate day) {
        return new DiagDayAggregate(day == null ? "" : day.toString(),
                0, 0, 0, 0, 0L, 0L, null);
    }

    /**
     * Incrémente l'agrégat du jour (appelé depuis le thread IO Trace).
     * Ignore les events {@code stress}. Au changement de date, snapshot
     * l'ancien jour puis repart à zéro.
     */
    public static void record(JSONObject event) {
        if (event == null || event.optBoolean("stress", false)) return;
        File f = file();
        if (f == null) return;
        try {
            LocalDate today = LocalDate.now();
            DiagDayAggregate cur = readCurrentRaw();
            if (cur != null && !cur.date.isEmpty() && !today.toString().equals(cur.date)) {
                cur.saveHistory();
                cur = empty(today);
            } else if (cur == null || cur.date.isEmpty()) {
                cur = empty(today);
            }
            long t = event.optLong("t", System.currentTimeMillis());
            String type = event.optString("type", "");
            int messages = cur.messages;
            int toolFails = cur.toolFails;
            int errors = cur.errors;
            List<JSONObject> details = new ArrayList<>(cur.errorDetails);
            boolean isToolFail = ("tool_end".equals(type) && !event.optBoolean("ok", true))
                    || "tool_failure_ctx".equals(type);
            boolean isError = "error".equals(type)
                    || ("llm_reply".equals(type)
                    && event.optString("text", "").startsWith("[error]"));
            if ("user_message".equals(type)) messages++;
            if ("tool_end".equals(type) && !event.optBoolean("ok", true)) toolFails++;
            if ("tool_failure_ctx".equals(type)) toolFails++;
            boolean copilotFail = false;
            if ("copilot_ui".equals(type)) {
                String kind = event.optString("kind", "");
                if ("matcher_miss".equals(kind) || "whitelist_block".equals(kind)
                        || "a11y_unavailable".equals(kind)
                        || "a11y_disconnected".equals(kind)
                        || "confirm_cancel".equals(kind)) {
                    toolFails++;
                    copilotFail = true;
                }
            }
            if ("error".equals(type)) errors++;
            if ("llm_reply".equals(type)
                    && event.optString("text", "").startsWith("[error]")) {
                errors++;
            }
            if (isToolFail || isError || copilotFail) {
                appendErrorDetail(details, detailFromEvent(event, t));
            }
            long first = cur.firstTs > 0 ? Math.min(cur.firstTs, t) : t;
            long last = Math.max(cur.lastTs, t);
            DiagDayAggregate next = new DiagDayAggregate(
                    today.toString(),
                    cur.events + 1,
                    messages,
                    toolFails,
                    errors,
                    first,
                    last,
                    details);
            next.save();
        } catch (Exception ignored) {
        }
    }

    private static void appendErrorDetail(List<JSONObject> details, JSONObject entry) {
        if (details == null || entry == null) return;
        details.add(entry);
        while (details.size() > MAX_ERROR_DETAILS) {
            details.remove(0);
        }
    }

    static JSONObject detailFromEvent(JSONObject event, long t) {
        String type = event != null ? event.optString("type", "") : "";
        String tool = firstNonEmpty(event, "tool", "name", "tool_name");
        String message = "";
        if ("error".equals(type)) {
            message = firstNonEmpty(event, "message", "text", "error", "msg");
        } else if ("tool_end".equals(type) || "tool_failure_ctx".equals(type)) {
            message = firstNonEmpty(event, "error", "message", "text", "result");
            if (TextUtils.isEmpty(message)) message = "échec";
        } else if ("copilot_ui".equals(type)) {
            message = firstNonEmpty(event, "detail", "reason", "kind");
            if (TextUtils.isEmpty(message)) message = "copilote";
            String pkg = event.optString("pkg", "");
            if (!TextUtils.isEmpty(pkg)) {
                message = message + " (" + pkg + ")";
            }
        } else if ("llm_reply".equals(type)) {
            message = event.optString("text", "");
            if (message.startsWith("[error]")) {
                message = message.substring("[error]".length()).trim();
            }
        } else {
            message = firstNonEmpty(event, "message", "text", "error");
        }
        message = clip(message, 220);
        try {
            return new JSONObject()
                    .put("t", t)
                    .put("type", type)
                    .put("tool", tool)
                    .put("message", message);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static String firstNonEmpty(JSONObject o, String... keys) {
        if (o == null || keys == null) return "";
        for (String k : keys) {
            if (k == null) continue;
            String v = o.optString(k, "").trim();
            if (!v.isEmpty() && !"null".equalsIgnoreCase(v)) return v;
        }
        return "";
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').replace('\r', ' ').trim();
        if (t.length() <= max) return t;
        return t.substring(0, Math.max(0, max - 1)) + "…";
    }

    /** Lit le fichier courant sans filtrer la date (pour rollover). */
    private static DiagDayAggregate readCurrentRaw() {
        File f = file();
        if (f == null || !f.exists()) return null;
        try {
            String raw = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                    StandardCharsets.UTF_8);
            return fromJson(new JSONObject(raw));
        } catch (Exception e) {
            return null;
        }
    }

    void save() {
        writeTo(file());
    }

    /** Snapshot daté — même schéma que {@link #FILE_NAME}. */
    void saveHistory() {
        if (date == null || date.isEmpty() || isEmpty()) return;
        try {
            LocalDate d = LocalDate.parse(date);
            writeTo(historyFile(d));
        } catch (Exception ignored) {
        }
    }

    private void writeTo(File f) {
        if (f == null) return;
        try {
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            JSONObject o = new JSONObject()
                    .put("date", date)
                    .put("events", events)
                    .put("messages", messages)
                    .put("tool_fails", toolFails)
                    .put("errors", errors)
                    .put("first_ts", firstTs)
                    .put("last_ts", lastTs);
            JSONArray arr = new JSONArray();
            for (JSONObject d : errorDetails) {
                if (d != null) arr.put(d);
            }
            o.put("error_details", arr);
            java.nio.file.Files.write(f.toPath(),
                    (o.toString() + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    /** Tests uniquement — remet le fichier courant à zéro pour la date donnée. */
    public static void resetForTests(LocalDate day) {
        empty(day).save();
        File hist = historyFile(day);
        if (hist != null && hist.exists()) {
            //noinspection ResultOfMethodCallIgnored
            hist.delete();
        }
    }

    static File file() {
        File trace = Trace.file();
        if (trace == null) return null;
        File parent = trace.getParentFile();
        if (parent == null) return null;
        return new File(parent, FILE_NAME);
    }

    static File historyFile(LocalDate day) {
        if (day == null) return null;
        File trace = Trace.file();
        if (trace == null) return null;
        File parent = trace.getParentFile();
        if (parent == null) return null;
        return new File(parent, "day-aggregate-" + day + ".json");
    }

    /** Plus petit {@code t} des events live, sinon agrégat. */
    public static long earliestTs(List<JSONObject> live, DiagDayAggregate agg) {
        long min = 0L;
        if (live != null) {
            for (JSONObject e : live) {
                if (e == null) continue;
                long t = e.optLong("t", 0L);
                if (t <= 0) continue;
                min = min == 0L ? t : Math.min(min, t);
            }
        }
        if (min == 0L && agg != null && agg.firstTs > 0) return agg.firstTs;
        return min;
    }

    public static String formatHm(long epochMs) {
        if (epochMs <= 0) return "";
        return Instant.ofEpochMilli(epochMs)
                .atZone(ZoneId.systemDefault())
                .toLocalTime()
                .format(HM);
    }

    public static String formatDayLabel(LocalDate day) {
        if (day == null) return "";
        return day.format(DD_MM);
    }

    /** Ex. « 5 événements conservés sur 61 ». */
    public String reconcileLine(int liveEventCount) {
        if (events <= 0 || liveEventCount >= events) return "";
        return liveEventCount + " événement" + (liveEventCount > 1 ? "s" : "")
                + " conservé" + (liveEventCount > 1 ? "s" : "")
                + " sur " + events;
    }

    /** Ex. « 61 msg, 2 échecs outil, 18 erreurs ». */
    public String countersPhrase() {
        StringBuilder sb = new StringBuilder();
        sb.append(messages).append(" msg");
        if (toolFails > 0) {
            sb.append(", ").append(toolFails)
                    .append(toolFails > 1 ? " échecs outil" : " échec outil");
        }
        if (errors > 0) {
            sb.append(", ").append(errors)
                    .append(errors > 1 ? " erreurs" : " erreur");
        }
        return sb.toString();
    }

    /**
     * Une ligne factuelle quand l'agrégat existe sans détail jsonl.
     * Ex. « 21/07 : 61 messages, 2 échecs outil, 18 erreurs — détail non conservé. »
     */
    public String factualNoDetailLine(LocalDate day) {
        LocalDate d = day;
        if (d == null) {
            try {
                d = LocalDate.parse(date);
            } catch (Exception e) {
                d = LocalDate.now();
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append(formatDayLabel(d)).append(" : ")
                .append(messages).append(messages > 1 ? " messages" : " message");
        if (toolFails > 0) {
            sb.append(", ").append(toolFails)
                    .append(toolFails > 1 ? " échecs outil" : " échec outil");
        }
        if (errors > 0) {
            sb.append(", ").append(errors)
                    .append(errors > 1 ? " erreurs" : " erreur");
        }
        sb.append(" — détail non conservé.");
        return sb.toString();
    }

    /**
     * Récit clair des erreurs du jour (pour {@code diag detail}).
     * Une ligne par entrée de {@link #errorDetails}.
     */
    public String narrateErrorDetails(LocalDate day) {
        LocalDate d = day;
        if (d == null) {
            try {
                d = LocalDate.parse(date);
            } catch (Exception e) {
                d = LocalDate.now();
            }
        }
        String label = formatDayLabel(d);
        int issues = issueCount();
        if (issues <= 0 && errorDetails.isEmpty()) {
            return "aucune erreur enregistrée le " + label;
        }
        if (errorDetails.isEmpty()) {
            return issues + " erreur" + (issues > 1 ? "s" : "")
                    + " enregistrée" + (issues > 1 ? "s" : "")
                    + " le " + label + ", détail non conservé";
        }
        StringBuilder sb = new StringBuilder();
        for (JSONObject e : errorDetails) {
            String line = formatErrorDetailLine(e);
            if (TextUtils.isEmpty(line)) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(line);
        }
        if (sb.length() == 0) {
            return issues > 0
                    ? issues + " erreur" + (issues > 1 ? "s" : "")
                    + " enregistrée" + (issues > 1 ? "s" : "")
                    + " le " + label + ", détail non conservé"
                    : "aucune erreur enregistrée le " + label;
        }
        if (issues > errorDetails.size()) {
            sb.append('\n').append('(').append(issues - errorDetails.size())
                    .append(" plus ancienne")
                    .append(issues - errorDetails.size() > 1 ? "s" : "")
                    .append(" non listée")
                    .append(issues - errorDetails.size() > 1 ? "s" : "")
                    .append(')');
        }
        return sb.toString();
    }

    /** Ex. « 14h03 · timeout Ollama · orion_call a échoué ». */
    public static String formatErrorDetailLine(JSONObject e) {
        if (e == null) return "";
        String hm = formatHm(e.optLong("t", 0L));
        String message = e.optString("message", "").trim();
        String tool = e.optString("tool", "").trim();
        String type = e.optString("type", "").trim();
        StringBuilder sb = new StringBuilder();
        if (!hm.isEmpty()) sb.append(hm);
        String mid = !message.isEmpty() ? message : humanizeType(type);
        if (!mid.isEmpty()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(mid);
        }
        if (!tool.isEmpty()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(tool).append(" a échoué");
        }
        return sb.toString().trim();
    }

    private static String humanizeType(String type) {
        if (type == null || type.isEmpty()) return "erreur";
        switch (type) {
            case "tool_end":
            case "tool_failure_ctx":
                return "échec outil";
            case "llm_reply":
                return "erreur LLM";
            case "error":
                return "erreur";
            default:
                return type.replace('_', ' ');
        }
    }

    /**
     * Compteurs dérivés d'une archive jsonl (même source que brief / weekly)
     * — sans modifier le fichier agrégat.
     */
    public static DiagDayAggregate fromDetailEvents(LocalDate day, List<JSONObject> events) {
        if (day == null) day = LocalDate.now();
        if (events == null || events.isEmpty()) return empty(day);
        DiagNlGenerator.DayStats st = DiagNlGenerator.statsOf(events);
        long first = 0L;
        long last = 0L;
        for (JSONObject e : events) {
            if (e == null) continue;
            long t = e.optLong("t", 0L);
            if (t <= 0) continue;
            first = first == 0L ? t : Math.min(first, t);
            last = Math.max(last, t);
        }
        return new DiagDayAggregate(
                day.toString(),
                events.size(),
                st.messages,
                st.toolFails,
                st.errors,
                first,
                last,
                null);
    }
}
