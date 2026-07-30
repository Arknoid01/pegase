package com.pegasuscorp.orbe.tools.life;

import com.pegasuscorp.orbe.tools.ToolTag;

import com.pegasuscorp.orbe.tools.ToolResult;

import com.pegasuscorp.orbe.tools.Tool;
import com.pegasuscorp.orbe.tools.ToolCallback;
import com.pegasuscorp.orbe.tools.device.CalendarWriter;

import android.content.Context;
import android.content.Intent;
import android.provider.CalendarContract;
import android.text.TextUtils;

import com.pegasuscorp.orbe.permissions.PermissionFlow;

import org.json.JSONObject;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

/**
 * Agenda Android — créer, lire, supprimer des événements.
 * ≠ {@link com.pegasuscorp.orbe.tools.device.AlarmTool} (réveil = sonne une fois).
 */
public final class AgendaTool implements Tool {

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");

    @Override
    public String id() {
        return "agenda";
    }

    @Override
    public ToolTag tag() {
        return ToolTag.AGENDA;
    }

    @Override
    public String description() {
        return "agenda(action:\"add\"|\"list\"|\"today\"|\"tomorrow\"|\"week\"|\"delete\"|\"open\", "
                + "title?:str, start?:str, end?:str, description?:str, location?:str, "
                + "reminder?:int, silent?:bool, event_id?:long) — Calendrier Android. "
                + "add : événement (start « demain 11h30 »). silent:true si permission calendrier. "
                + "today/tomorrow/week/list : lecture. delete : par event_id ou title+when. "
                + "open : ouvre l'app Agenda.";
    }

    @Override
    public void execute(Context ctx, JSONObject params, ToolCallback cb) {
        if (params == null) params = new JSONObject();
        String action = params.optString("action", "add").trim().toLowerCase(Locale.ROOT);
        if (action.isEmpty()) action = "add";

        switch (action) {
            case "list":
            case "show":
                executeList(ctx, cb, "Agenda", CalendarQuery.today(ctx));
                return;
            case "today":
            case "aujourdhui":
            case "aujourd'hui":
                executeList(ctx, cb, "Aujourd'hui", CalendarQuery.today(ctx));
                return;
            case "tomorrow":
            case "demain":
                executeList(ctx, cb, "Demain", CalendarQuery.tomorrow(ctx));
                return;
            case "week":
            case "semaine":
                executeList(ctx, cb, "Cette semaine", CalendarQuery.week(ctx));
                return;
            case "open":
                openCalendar(ctx, cb);
                return;
            case "delete":
            case "remove":
                executeDelete(ctx, params, cb);
                return;
            case "add":
            case "create":
            case "set":
                executeAdd(ctx, params, cb);
                return;
            default:
                cb.onError("Action agenda inconnue. Utilise add, today, tomorrow, week, delete, open.");
        }
    }

    private void executeList(Context ctx, ToolCallback cb, String header,
            List<CalendarQuery.Event> events) {
        if (!PermissionFlow.hasCalendar(ctx)) {
            PermissionFlow.ensureCalendar(ctx);
            cb.onError("J'ai besoin de la permission Calendrier pour lire ton agenda.");
            return;
        }
        String body = CalendarQuery.summarizeList(events,
                "Rien de prévu dans le calendrier.");
        cb.onSuccessAndExit(ToolResult.text(header + " :\n" + body));
    }

    private void executeDelete(Context ctx, JSONObject params, ToolCallback cb) {
        if (!PermissionFlow.hasCalendar(ctx)) {
            PermissionFlow.ensureCalendar(ctx);
            cb.onError("Permission calendrier requise pour supprimer un événement.");
            return;
        }
        long eventId = params.optLong("event_id", -1);
        if (eventId > 0) {
            if (CalendarQuery.deleteEvent(ctx, eventId)) {
                cb.onSuccessAndExit(ToolResult.text("Événement supprimé."));
            } else {
                cb.onError("Impossible de supprimer cet événement.");
            }
            return;
        }
        String title = firstNonEmpty(params, "title", "titre", "name");
        if (TextUtils.isEmpty(title)) {
            cb.onError("Indique event_id ou le titre à supprimer.");
            return;
        }
        String when = firstNonEmpty(params, "start", "when", "day");
        LocalDate day = when.contains("demain")
                ? LocalDate.now(PARIS).plusDays(1) : LocalDate.now(PARIS);
        long start = day.atStartOfDay(PARIS).toInstant().toEpochMilli();
        long end = day.plusDays(1).atStartOfDay(PARIS).toInstant().toEpochMilli();
        CalendarQuery.Event match = CalendarQuery.findByTitle(ctx, title, start, end);
        if (match == null) {
            cb.onError("Aucun événement « " + title + " » trouvé ce jour-là.");
            return;
        }
        if (CalendarQuery.deleteEvent(ctx, match.eventId)) {
            cb.onSuccessAndExit(ToolResult.text(
                    "Supprimé : " + match.title + " (" + CalendarQuery.formatTime(match.beginMs) + ")."));
        } else {
            cb.onError("Suppression impossible.");
        }
    }

    private void executeAdd(Context ctx, JSONObject params, ToolCallback cb) {
        String title = firstNonEmpty(params, "title", "titre", "name", "sujet");
        if (TextUtils.isEmpty(title)) {
            cb.onError("Indique le titre de l'événement.");
            return;
        }

        String startRaw = firstNonEmpty(params, "start", "debut", "début", "when", "datetime");
        if (startRaw.isEmpty()) {
            String date = params.optString("date", "").trim();
            String time = params.optString("time", "").trim();
            if (!date.isEmpty() || !time.isEmpty()) {
                startRaw = (date + " " + time).trim();
            }
        }
        if (startRaw.isEmpty()) {
            cb.onError("Indique le début (ex. « demain 11h30 »).");
            return;
        }

        long startMs = FrenchDateTimeParser.parseToEpochMs(startRaw);
        if (startMs <= 0) {
            cb.onError("Je n'ai pas compris la date : « " + startRaw + " ».");
            return;
        }

        String endRaw = firstNonEmpty(params, "end", "fin");
        long endMs;
        if (!endRaw.isEmpty()) {
            endMs = FrenchDateTimeParser.parseToEpochMs(endRaw);
            if (endMs <= startMs) endMs = startMs + 60L * 60L * 1000L;
        } else if (params.has("duration_min")) {
            int dur = params.optInt("duration_min", 60);
            if (dur < 1) dur = 60;
            endMs = startMs + dur * 60L * 1000L;
        } else {
            endMs = startMs + 60L * 60L * 1000L;
        }

        String description = firstNonEmpty(params, "description", "details", "détails", "note");
        String location = firstNonEmpty(params, "location", "lieu", "place");
        int reminder = params.has("reminder") ? params.optInt("reminder", 15) : 15;
        if (reminder < 0) reminder = 15;
        boolean silent = params.optBoolean("silent", true);

        if (silent && PermissionFlow.hasCalendar(ctx)) {
            long eventId = CalendarWriter.insertEvent(ctx, title, startMs, endMs,
                    description, location, reminder);
            if (eventId > 0) {
                String when = FrenchDateTimeParser.formatSpoken(startMs);
                cb.onSuccessAndExit(ToolResult.text(
                        "Événement ajouté : " + title + (when.isEmpty() ? "." : ", " + when + ".")));
                return;
            }
        }

        if (!PermissionFlow.hasCalendar(ctx)) {
            PermissionFlow.ensureCalendar(ctx);
        }

        Intent intent = new Intent(Intent.ACTION_INSERT);
        intent.setData(CalendarContract.Events.CONTENT_URI);
        intent.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMs);
        intent.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMs);
        intent.putExtra(CalendarContract.Events.TITLE, title);
        if (!description.isEmpty()) {
            intent.putExtra(CalendarContract.Events.DESCRIPTION, description);
        }
        if (!location.isEmpty()) {
            intent.putExtra(CalendarContract.Events.EVENT_LOCATION, location);
        }
        intent.putExtra(CalendarContract.Reminders.MINUTES, reminder);
        intent.putExtra(CalendarContract.Events.HAS_ALARM, true);
        intent.putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, false);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        if (intent.resolveActivity(ctx.getPackageManager()) == null) {
            cb.onError("Aucune app Agenda / Calendrier trouvée.");
            return;
        }
        try {
            ctx.startActivity(intent);
        } catch (Exception e) {
            cb.onError("Impossible d'ouvrir l'agenda : "
                    + (e.getMessage() == null ? "erreur" : e.getMessage()));
            return;
        }

        String when = FrenchDateTimeParser.formatSpoken(startMs);
        cb.onSuccessAndExit(ToolResult.text(
                "Événement prêt dans l'agenda : " + title
                        + (when.isEmpty() ? "." : ", " + when + ".")
                        + " Confirme l'enregistrement dans l'app."));
    }

    private static void openCalendar(Context ctx, ToolCallback cb) {
        long now = System.currentTimeMillis();
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setData(CalendarContract.CONTENT_URI.buildUpon()
                        .appendPath("time")
                        .appendPath(String.valueOf(now))
                        .build())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (intent.resolveActivity(ctx.getPackageManager()) != null) {
            ctx.startActivity(intent);
            cb.onSuccessAndExit(ToolResult.text("J'ouvre ton agenda."));
            return;
        }
        cb.onError("Aucune app Agenda trouvée.");
    }

    private static String firstNonEmpty(JSONObject params, String... keys) {
        if (params == null || keys == null) return "";
        for (String k : keys) {
            String v = params.optString(k, "").trim();
            if (!v.isEmpty()) return v;
        }
        return "";
    }
}
