package com.pegasuscorp.orbe.tools.life;

import com.pegasuscorp.orbe.tools.ToolTag;

import com.pegasuscorp.orbe.tools.ToolResult;

import com.pegasuscorp.orbe.tools.Tool;
import com.pegasuscorp.orbe.tools.ToolCallback;

import android.content.Context;
import android.content.Intent;
import android.provider.CalendarContract;
import android.text.TextUtils;

import com.pegasuscorp.orbe.permissions.PermissionFlow;

import org.json.JSONObject;

import java.util.Locale;

/**
 * Crée un événement dans Google Agenda (Intent ACTION_INSERT).
 * Ne pas confondre avec {@link AlarmTool} (alarme = sonne une fois).
 */
public final class AgendaTool implements Tool {

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
        return "agenda(title:str, start:str, end?:str, description?:str, location?:str, "
                + "reminder?:int) — Crée un événement dans Google Agenda. "
                + "UTILISER pour : « ajoute au calendrier », « mets dans l'agenda », "
                + "« réunion », « rendez-vous », « événement », « rappel dans l'agenda ». "
                + "NE PAS confondre avec alarm (alarme = sonne une fois) "
                + "vs agenda (événement = visible dans le calendrier). "
                + "start : « demain 11h30 », « lundi 14h », « 2026-07-20 11:30 », "
                + "« dans 2 heures ». end optionnel (= start + 1h). "
                + "reminder = minutes avant (défaut 15). "
                + "Exemple : Buffet de midi demain 11h30 → "
                + "agenda(title:\"Buffet de midi\", start:\"demain 11:30\", reminder:30)";
    }

    @Override
    public void execute(Context ctx, JSONObject params, ToolCallback cb) {
        if (params == null) params = new JSONObject();

        String title = firstNonEmpty(params, "title", "titre", "name", "sujet");
        if (TextUtils.isEmpty(title)) {
            cb.onError("Indique le titre de l'événement.");
            return;
        }

        String startRaw = firstNonEmpty(params, "start", "debut", "début", "when", "datetime");
        // Compat ancien calendar(title, date, time)
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

        if (!PermissionFlow.hasCalendar(ctx)) {
            PermissionFlow.ensureCalendar(ctx);
            // ACTION_INSERT fonctionne souvent sans grant ; on continue
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
        String spoken = "Événement ajouté : " + title
                + (when.isEmpty() ? "." : ", " + when + ".");
        cb.onSuccessAndExit(ToolResult.text(spoken));
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
