package com.pegasuscorp.orbe.diag;

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioManager;

import androidx.core.content.ContextCompat;

import com.pegasuscorp.orbe.voice.KwsAudioRouteManager;
import com.pegasuscorp.orbe.voice.KwsCrashGuard;
import com.pegasuscorp.orbe.voice.KwsModelStore;
import com.pegasuscorp.orbe.voice.PegaseWakeStore;
import com.pegasuscorp.orbe.voice.VoiceMuteStore;
import com.pegasuscorp.orbe.voice.VoiceWakeClient;
import com.pegasuscorp.orbe.voice.WakeHealthStatus;
import com.pegasuscorp.orbe.voice.WakeOwwStore;

import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Instantané santé / debug pour {@link com.pegasuscorp.orbe.DebugDashboardActivity}.
 * Lit les JSONL existants + APIs live — aucun nouveau backend de log.
 */
public final class DebugHealthSnapshot {

    public enum Window {
        H1("1h", "1 heure", 3_600_000L),
        H6("6h", "6 heures", 6L * 3_600_000L),
        H24("24h", "24 heures", 24L * 3_600_000L),
        D7("7j", "7 jours", 7L * 86_400_000L),
        ALL("tout", "Tout", 0L);

        public final String id;
        public final String label;
        public final long durationMs;

        Window(String id, String label, long durationMs) {
            this.id = id;
            this.label = label;
            this.durationMs = durationMs;
        }

        public static Window fromId(String id) {
            if (id == null) return H24;
            for (Window w : values()) {
                if (w.id.equals(id)) return w;
            }
            return H24;
        }

        public long sinceMs(long now) {
            if (durationMs <= 0L) return 0L;
            return now - durationMs;
        }
    }

    public enum Sort {
        NEWEST("recent", "Plus récents"),
        OLDEST("ancien", "Plus anciens");

        public final String id;
        public final String label;

        Sort(String id, String label) {
            this.id = id;
            this.label = label;
        }

        public static Sort fromId(String id) {
            if (id == null) return NEWEST;
            for (Sort s : values()) {
                if (s.id.equals(id)) return s;
            }
            return NEWEST;
        }

        public boolean newestFirst() {
            return this == NEWEST;
        }
    }

    public enum ProblemKind {
        ACTIVE,
        RESOLVED_AUTO,
        ACKED
    }

    public static final class Problem {
        public final String id;
        public final String title;
        public final String detail;
        public final long eventAtMs;
        public final ProblemKind kind;

        public Problem(String id, String title, String detail, long eventAtMs, ProblemKind kind) {
            this.id = id;
            this.title = title;
            this.detail = detail;
            this.eventAtMs = eventAtMs;
            this.kind = kind;
        }

        public String displayLine() {
            StringBuilder sb = new StringBuilder(title);
            if (detail != null && !detail.isEmpty()) {
                sb.append('\n').append(detail);
            }
            if (eventAtMs > 0L) {
                sb.append("\n· ").append(formatClock(eventAtMs));
            }
            return sb.toString();
        }
    }

    public final String generatedAt;
    public final Window window;
    public final Sort sort;
    public final String healthLine;
    public final String micLine;
    public final String wakeLine;
    public final String statsLine;
    public final String lastScoLine;
    public final String lastWakeSttLine;
    public final String lastWeatherLine;
    public final String lastRouteChangeLine;
    /** Compat UI : titres des problèmes ACTIVE uniquement. */
    public final List<String> problems;
    public final List<Problem> activeProblems;
    public final List<Problem> resolvedProblems;
    public final List<String> recentCrashes;
    public final List<String> recentErrors;
    public final List<String> recentKwsEvents;
    public final List<String> recentWakeHits;
    public final List<String> recentScoEvents;

    private DebugHealthSnapshot(
            String generatedAt,
            Window window,
            Sort sort,
            String healthLine,
            String micLine,
            String wakeLine,
            String statsLine,
            String lastScoLine,
            String lastWakeSttLine,
            String lastWeatherLine,
            String lastRouteChangeLine,
            List<String> problems,
            List<Problem> activeProblems,
            List<Problem> resolvedProblems,
            List<String> recentCrashes,
            List<String> recentErrors,
            List<String> recentKwsEvents,
            List<String> recentWakeHits,
            List<String> recentScoEvents) {
        this.generatedAt = generatedAt;
        this.window = window;
        this.sort = sort;
        this.healthLine = healthLine;
        this.micLine = micLine;
        this.wakeLine = wakeLine;
        this.statsLine = statsLine;
        this.lastScoLine = lastScoLine;
        this.lastWakeSttLine = lastWakeSttLine;
        this.lastWeatherLine = lastWeatherLine;
        this.lastRouteChangeLine = lastRouteChangeLine;
        this.problems = problems;
        this.activeProblems = activeProblems;
        this.resolvedProblems = resolvedProblems;
        this.recentCrashes = recentCrashes;
        this.recentErrors = recentErrors;
        this.recentKwsEvents = recentKwsEvents;
        this.recentWakeHits = recentWakeHits;
        this.recentScoEvents = recentScoEvents;
    }

    public static DebugHealthSnapshot capture(Context ctx) {
        Window window = Window.fromId(DebugProblemAckStore.getWindow(ctx));
        Sort sort = Sort.fromId(DebugProblemAckStore.getSort(ctx));
        return capture(ctx, window, sort);
    }

    public static DebugHealthSnapshot capture(Context ctx, Window window, Sort sort) {
        Context app = ctx.getApplicationContext();
        long now = System.currentTimeMillis();
        long since = window.sinceMs(now);
        boolean newestFirst = sort.newestFirst();

        ArrayList<Problem> raw = new ArrayList<>();

        boolean micPerm = ContextCompat.checkSelfPermission(app,
                android.Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        if (!micPerm) {
            raw.add(live("live:mic_perm", "Permission micro absente",
                    "RECORD_AUDIO refusée — wake / STT impossibles", now));
        }

        boolean muted = VoiceMuteStore.isMuted(app);
        boolean wakeEnabled = PegaseWakeStore.isEnabled(app);
        if (muted) {
            raw.add(live("live:muted", "Micro coupé (mute)",
                    "Réactive le micro dans Réglages / notif", now));
        }
        if (!wakeEnabled) {
            raw.add(live("live:wake_off", "Wake désactivé dans les réglages",
                    "Préférence enabled absente/OFF — pas un crashGuard."
                            + " « gentle » = mode doux (défaut), pas une coupure auto.",
                    now));
        }

        boolean crashGuard = KwsCrashGuard.shouldDisableKws(app);
        if (crashGuard) {
            raw.add(live("live:crash_guard", "Coupe-circuit KWS déclenché",
                    "Trop de crashs rapides — reset dans Réglages → Voice", now));
        }

        WakeHealthStatus health = VoiceWakeClient.get().getCachedWakeHealth();
        String healthLabel;
        switch (health) {
            case LISTENING:
                healthLabel = "Écoute active";
                break;
            case PROBLEM:
                healthLabel = "Problème wake";
                raw.add(live("live:wake_health", "WakeHealth = PROBLEM",
                        "Le service wake signale un souci — voir events voice", now));
                break;
            default:
                healthLabel = "Wake off / inactif";
                break;
        }

        String routeLive = "—";
        boolean scoLive = false;
        boolean wantBt = false;
        try {
            KwsAudioRouteManager route = KwsAudioRouteManager.getInstance(app);
            routeLive = route.describeRoute();
            wantBt = route.wantsBluetoothMic();
            scoLive = route.isScoLive();
        } catch (Exception e) {
            routeLive = "indisponible: " + e.getMessage();
        }

        AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        boolean music = am != null && am.isMusicActive();
        boolean btScoAm = am != null && am.isBluetoothScoOn();
        String micLine = "Route: " + routeLive
                + "\nSCO live: " + (scoLive ? "oui" : "non")
                + " · AudioManager SCO: " + (btScoAm ? "on" : "off")
                + " · veut BT: " + (wantBt ? "oui" : "non")
                + "\nPerm micro: " + (micPerm ? "ok" : "NON")
                + " · mute: " + (muted ? "oui" : "non")
                + " · autre audio: " + (music ? "oui" : "non");
        if (wantBt && !scoLive) {
            raw.add(live("live:sco_gap", "BT détecté mais SCO non établi",
                    "Souvent phoneForced — wake sur micro téléphone", now));
        }

        float owwThr = PegaseWakeStore.getOwwThreshold(app);
        String oww = WakeOwwStore.isModelReady(app) ? "OWW prêt" : "OWW absent";
        String sherpa = KwsModelStore.isModelReady(app) ? "Sherpa prêt" : "Sherpa absent";
        String wakeLine = healthLabel
                + " · wake " + (wakeEnabled ? "ON" : "OFF")
                + " · gentle=" + (PegaseWakeStore.isGentleMode(app) ? "oui" : "non")
                + " (gentle ≠ coupure auto)"
                + "\n" + oww + " (seuil " + String.format(Locale.US, "%.2f", owwThr) + ")"
                + " · " + sherpa
                + " · crashGuard=" + (crashGuard ? "TRIPPED" : "ok");

        File kwsFile = PegaseDiagLog.kwsLogFile(app);
        File crashesFile = PegaseDiagLog.crashLogFile(app);
        File traceFile = Trace.file();

        int scan = scanLinesFor(window);
        List<JSONObject> kwsAll = DiagLogReader.filterSince(
                DiagLogReader.tailJson(kwsFile, scan), since);
        List<JSONObject> crashAll = DiagLogReader.filterSince(
                DiagLogReader.tailJson(crashesFile, Math.min(scan, 200)), since);
        List<JSONObject> traceAll = DiagLogReader.filterSince(
                DiagLogReader.tailJson(traceFile, Math.min(scan, 400)), since);

        // ── SCO fail → auto-résolu si succès plus récent ou SCO live ──
        JSONObject lastScoStart = DiagLogReader.lastMatching(kwsAll, "sco_service_start");
        JSONObject lastScoOk = null;
        JSONObject lastScoFail = null;
        for (int i = kwsAll.size() - 1; i >= 0; i--) {
            JSONObject o = kwsAll.get(i);
            if (!"sco_service_start".equals(o.optString("event"))) continue;
            if (o.has("ok") && o.optBoolean("ok")) {
                if (lastScoOk == null) lastScoOk = o;
            } else if (o.has("ok") && !o.optBoolean("ok")) {
                if (lastScoFail == null) lastScoFail = o;
            }
            if (lastScoOk != null && lastScoFail != null) break;
        }
        if (lastScoFail != null) {
            long failTs = DiagLogReader.parseTsMs(lastScoFail);
            long okTs = lastScoOk != null ? DiagLogReader.parseTsMs(lastScoOk) : 0L;
            boolean autoOk = scoLive || (okTs > failTs);
            String detail = formatEvent(lastScoFail);
            if (autoOk) {
                detail += scoLive
                        ? "\n→ résolu : SCO live maintenant"
                        : "\n→ résolu : sco_service_start ok après (" + formatEvent(lastScoOk) + ")";
                raw.add(new Problem("event:sco_fail", "SCO start avait échoué",
                        detail, failTs, ProblemKind.RESOLVED_AUTO));
            } else {
                raw.add(new Problem("event:sco_fail",
                        "Dernier sco_service_start a échoué",
                        detail + "\n(wake souvent en phoneForced)",
                        failTs, ProblemKind.ACTIVE));
            }
        }

        JSONObject scoSettle = DiagLogReader.lastMatching(kwsAll, "stt_sco_settle", "stt_prepare_done");
        String lastScoLine = "sco_service_start: " + formatEvent(lastScoStart)
                + "\nsettle/prepare: " + formatEvent(scoSettle);

        // ── Wake / STT ──
        JSONObject wakeHit = DiagLogReader.lastMatching(kwsAll,
                "wake_detected", "kws_wake_detected");
        JSONObject sttErr = DiagLogReader.lastMatching(kwsAll, "stt_error");
        JSONObject sttReady = DiagLogReader.lastMatching(kwsAll, "stt_ready");
        JSONObject sttOpenFail = DiagLogReader.lastMatching(kwsAll, "stt_open_failed");
        JSONObject backendBt = DiagLogReader.lastMatching(kwsAll, "wake_backend_bt_prefer_sherpa");
        JSONObject phoneForced = DiagLogReader.lastMatching(kwsAll, "phoneForced", "forcePhoneBuiltin");

        StringBuilder wakeStt = new StringBuilder();
        if (wakeHit != null) {
            wakeStt.append("Dernier wake: ").append(formatEvent(wakeHit));
        } else {
            wakeStt.append("Aucun wake_detected dans la fenêtre");
        }
        if (sttReady != null) wakeStt.append("\nSTT ready: ").append(formatEvent(sttReady));
        if (sttErr != null) wakeStt.append("\nSTT error: ").append(formatEvent(sttErr));
        if (sttOpenFail != null) wakeStt.append("\nSTT open fail: ").append(formatEvent(sttOpenFail));
        if (backendBt != null) wakeStt.append("\nBackend BT: ").append(formatEvent(backendBt));
        if (phoneForced != null) wakeStt.append("\nphoneForced: ").append(formatEvent(phoneForced));

        if (sttErr != null) {
            long errTs = DiagLogReader.parseTsMs(sttErr);
            long readyTs = sttReady != null ? DiagLogReader.parseTsMs(sttReady) : 0L;
            String title = "STT error " + sttErr.optInt("error", -1)
                    + (sttErr.optString("label").isEmpty() ? "" : " — " + sttErr.optString("label"));
            String detail = formatEvent(sttErr);
            if (readyTs > errTs) {
                detail += "\n→ résolu : stt_ready après";
                raw.add(new Problem("event:stt_error", title, detail, errTs, ProblemKind.RESOLVED_AUTO));
            } else {
                raw.add(new Problem("event:stt_error", title, detail, errTs, ProblemKind.ACTIVE));
            }
        }
        if (sttOpenFail != null) {
            long failTs = DiagLogReader.parseTsMs(sttOpenFail);
            JSONObject sttOpenDone = DiagLogReader.lastMatching(kwsAll, "stt_open_done");
            long doneTs = sttOpenDone != null ? DiagLogReader.parseTsMs(sttOpenDone) : 0L;
            String detail = formatEvent(sttOpenFail);
            if (doneTs > failTs) {
                detail += "\n→ résolu : stt_open_done après";
                raw.add(new Problem("event:stt_open_fail", "STT open avait échoué",
                        detail, failTs, ProblemKind.RESOLVED_AUTO));
            } else {
                raw.add(new Problem("event:stt_open_fail", "STT open a échoué",
                        detail, failTs, ProblemKind.ACTIVE));
            }
        }

        JSONObject weather = DiagLogReader.lastMatching(kwsAll, "weather_location");
        String lastWeatherLine = weather != null
                ? formatEventRich(weather)
                : "Pas d'événement weather_location dans la fenêtre";

        JSONObject routeChange = DiagLogReader.lastMatching(kwsAll,
                "audio_route_changed", "audio_route_ping", "audio_route_suppressed");
        String lastRouteChangeLine = formatEvent(routeChange);

        // Crashes dans la fenêtre
        List<JSONObject> crashHits = DiagLogReader.lastMatchingMany(crashAll, 12, "crash");
        if (!crashHits.isEmpty()) {
            JSONObject last = crashHits.get(0);
            long lastTs = DiagLogReader.parseTsMs(last);
            raw.add(new Problem("event:crash",
                    crashHits.size() + " crash(s) dans la fenêtre",
                    formatCrash(last),
                    lastTs, ProblemKind.ACTIVE));
        }

        // Appliquer acks manuels
        ArrayList<Problem> active = new ArrayList<>();
        ArrayList<Problem> resolved = new ArrayList<>();
        for (Problem p : raw) {
            if (p.kind == ProblemKind.RESOLVED_AUTO) {
                resolved.add(p);
                continue;
            }
            if (DebugProblemAckStore.isAcked(app, p.id, p.eventAtMs)) {
                resolved.add(new Problem(p.id, p.title + " (acquitté)",
                        p.detail, p.eventAtMs, ProblemKind.ACKED));
            } else {
                active.add(p);
            }
        }
        sortProblems(active, newestFirst);
        sortProblems(resolved, newestFirst);

        ArrayList<String> problemTitles = new ArrayList<>();
        for (Problem p : active) problemTitles.add(p.title);

        ArrayList<String> recentCrashes = new ArrayList<>();
        List<JSONObject> crashSorted = new ArrayList<>(crashHits);
        DiagLogReader.sortByTs(crashSorted, newestFirst);
        for (JSONObject c : crashSorted) recentCrashes.add(formatCrash(c));

        ArrayList<String> recentErrors = new ArrayList<>();
        List<JSONObject> errs = DiagLogReader.lastMatchingMany(traceAll, 16, "error");
        DiagLogReader.sortByTs(errs, newestFirst);
        for (JSONObject e : errs) recentErrors.add(formatTraceError(e));

        ArrayList<String> recentKws = formatList(kwsAll, 20, newestFirst, null);
        ArrayList<String> recentWakeHits = formatList(kwsAll, 10, newestFirst,
                new String[]{"wake_detected", "kws_wake_detected"});
        ArrayList<String> recentSco = formatList(kwsAll, 10, newestFirst,
                new String[]{"sco_service_start", "stt_sco_settle", "stt_prepare_done"});

        int wakeN = DiagLogReader.countMatching(kwsAll, "wake_detected", "kws_wake_detected");
        int scoFailN = 0;
        int scoOkN = 0;
        for (JSONObject o : kwsAll) {
            if (!"sco_service_start".equals(o.optString("event"))) continue;
            if (o.optBoolean("ok", false)) scoOkN++;
            else if (o.has("ok")) scoFailN++;
        }
        int sttErrN = DiagLogReader.countMatching(kwsAll, "stt_error");
        int crashN = crashHits.size();
        String statsLine = "Fenêtre " + window.label
                + " · tri " + sort.label.toLowerCase(Locale.FRANCE)
                + "\nEvents voice: " + kwsAll.size()
                + " · wakes: " + wakeN
                + " · SCO ok/fail: " + scoOkN + "/" + scoFailN
                + "\nSTT errors: " + sttErrN
                + " · crashes: " + crashN
                + " · erreurs trace: " + errs.size()
                + "\nActifs: " + active.size()
                + " · résolus/acquittés: " + resolved.size();

        SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss", Locale.FRANCE);
        fmt.setTimeZone(TimeZone.getDefault());

        return new DebugHealthSnapshot(
                fmt.format(new Date(now)),
                window,
                sort,
                healthLabel + (active.isEmpty()
                        ? " · rien de bloquant"
                        : " · " + active.size() + " alerte(s)"),
                micLine,
                wakeLine,
                statsLine,
                lastScoLine,
                wakeStt.toString(),
                lastWeatherLine,
                lastRouteChangeLine,
                problemTitles,
                active,
                resolved,
                recentCrashes,
                recentErrors,
                recentKws,
                recentWakeHits,
                recentSco);
    }

    private static Problem live(String id, String title, String detail, long now) {
        return new Problem(id, title, detail, now, ProblemKind.ACTIVE);
    }

    private static void sortProblems(List<Problem> list, boolean newestFirst) {
        list.sort((a, b) -> {
            int c = Long.compare(a.eventAtMs, b.eventAtMs);
            return newestFirst ? -c : c;
        });
    }

    private static int scanLinesFor(Window w) {
        switch (w) {
            case H1: return 400;
            case H6: return 800;
            case H24: return 1200;
            case D7: return 2500;
            default: return 4000;
        }
    }

    private static ArrayList<String> formatList(
            List<JSONObject> all, int max, boolean newestFirst, String[] filter) {
        ArrayList<JSONObject> picked = new ArrayList<>();
        if (filter == null) {
            picked.addAll(all);
        } else {
            for (JSONObject o : all) {
                if (DiagLogReader.matches(o, filter)) picked.add(o);
            }
        }
        DiagLogReader.sortByTs(picked, newestFirst);
        ArrayList<String> out = new ArrayList<>();
        int n = Math.min(max, picked.size());
        for (int i = 0; i < n; i++) out.add(formatEventRich(picked.get(i)));
        return out;
    }

    private static String formatEvent(JSONObject o) {
        if (o == null) return "—";
        String ts = o.optString("ts", "");
        String event = o.optString("event", o.optString("type", "?"));
        String route = o.optString("route", "");
        StringBuilder sb = new StringBuilder();
        if (!ts.isEmpty()) sb.append(shortTs(ts)).append(" ");
        sb.append(event);
        if (o.has("ok")) sb.append(" ok=").append(o.optBoolean("ok"));
        if (o.has("source")) sb.append(" src=").append(o.optString("source"));
        if (o.has("error")) sb.append(" err=").append(o.opt("error"));
        if (o.has("label")) sb.append(" ").append(o.optString("label"));
        if (o.has("elapsed_ms")) sb.append(" +").append(o.opt("elapsed_ms")).append("ms");
        if (o.has("reason")) sb.append(" reason=").append(o.optString("reason"));
        if (o.has("backend")) sb.append(" be=").append(o.optString("backend"));
        if (o.has("fail_reason")) sb.append(" fail=").append(o.optString("fail_reason"));
        if (o.has("phases")) sb.append(" phases=").append(o.optString("phases"));
        if (o.has("sco_states")) sb.append(" states=").append(o.optString("sco_states"));
        if (o.has("hfp_devices")) sb.append(" hfp=").append(o.opt("hfp_devices"));
        if (o.has("music_active")) sb.append(" music=").append(o.opt("music_active"));
        if (o.has("a2dp_out")) sb.append(" a2dp=").append(o.opt("a2dp_out"));
        if (o.has("hfp_audio")) sb.append(" hfpAudio=").append(o.opt("hfp_audio"));
        if (!route.isEmpty()) {
            String shortRoute = route.length() > 72 ? route.substring(0, 72) + "…" : route;
            sb.append("\n  ").append(shortRoute);
        }
        if (o.has("process")) sb.append("\n  ").append(o.optString("process"));
        return sb.toString();
    }

    private static String formatEventRich(JSONObject o) {
        if (o == null) return "—";
        String base = formatEvent(o);
        StringBuilder extra = new StringBuilder();
        String[] keys = {
                "place", "source", "live_used_by_weather", "lat", "lon",
                "want_bt", "sco_prepared", "wake_service_hold", "transition_id",
                "score", "threshold", "engine", "command"
        };
        for (String k : keys) {
            if (!o.has(k)) continue;
            Object v = o.opt(k);
            if (v == null || "".equals(String.valueOf(v))) continue;
            if (extra.length() > 0) extra.append(" · ");
            extra.append(k).append('=').append(v);
        }
        if (extra.length() == 0) return base;
        return base + "\n  " + extra;
    }

    private static String formatCrash(JSONObject o) {
        String ts = o.optString("ts", "");
        String msg = o.optString("message", "");
        String th = o.optString("throwable", "");
        String thread = o.optString("thread", "");
        String stack = o.optString("stack", "");
        String firstStack = "";
        if (!stack.isEmpty()) {
            int nl = stack.indexOf('\n');
            firstStack = nl > 0 ? stack.substring(0, Math.min(nl, 140)) : stack;
            if (firstStack.length() > 140) firstStack = firstStack.substring(0, 140) + "…";
        }
        return shortTs(ts) + " " + th
                + (msg.isEmpty() ? "" : " — " + msg)
                + (thread.isEmpty() ? "" : " [" + thread + "]")
                + (firstStack.isEmpty() ? "" : "\n  " + firstStack);
    }

    private static String formatTraceError(JSONObject o) {
        String ts = o.optString("ts", o.optString("t", ""));
        String msg = o.optString("message", o.optString("msg", o.optString("error", "")));
        String where = o.optString("where", o.optString("tag", ""));
        return shortTs(ts) + (where.isEmpty() ? "" : " " + where)
                + (msg.isEmpty() ? " erreur" : " " + msg);
    }

    private static String shortTs(String ts) {
        if (ts == null || ts.isEmpty()) return "";
        int t = ts.indexOf('T');
        if (t >= 0 && ts.length() >= t + 9) {
            String day = ts.length() >= 10 ? ts.substring(5, 10) + " " : "";
            return day + ts.substring(t + 1, Math.min(ts.length(), t + 9));
        }
        return ts.length() > 19 ? ts.substring(0, 19) : ts;
    }

    private static String formatClock(long ms) {
        if (ms <= 0L) return "";
        SimpleDateFormat fmt = new SimpleDateFormat("dd/MM HH:mm:ss", Locale.FRANCE);
        fmt.setTimeZone(TimeZone.getDefault());
        return fmt.format(new Date(ms));
    }
}
