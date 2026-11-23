package com.pegasuscorp.orbe.tools.life;

import com.pegasuscorp.orbe.tools.ToolTag;

import com.pegasuscorp.orbe.tools.ToolResult;

import com.pegasuscorp.orbe.tools.Tool;
import com.pegasuscorp.orbe.tools.ToolCallback;

import android.content.Context;
import android.text.TextUtils;

import com.pegasuscorp.orbe.notepad.NotepadDateHelper;
import com.pegasuscorp.orbe.notepad.NotepadStore;

import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Agenda = le bloc-notes daté (plus de calendrier Android, choix de Yannick le 2026-11-23).
 * Même id « agenda » pour garder le routage local et les exemples ; tout est rangé
 * dans {@link NotepadStore} : avec une date → ce jour-là (rappel si une heure est dite),
 * sans date → aujourd'hui.
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
        return "agenda(action:\"add\"|\"today\"|\"tomorrow\"|\"week\"|\"delete\", "
                + "title?:str, start?:str, location?:str) — Agenda personnel (rangé dans le "
                + "bloc-notes, à la bonne date). add : start tel que dit (« demain 11h30 », "
                + "« vendredi ») ; sans date → aujourd'hui. today/tomorrow/week : ce qui est prévu. "
                + "delete : par title.";
    }

    @Override
    public void execute(Context ctx, JSONObject params, ToolCallback cb) {
        if (params == null) params = new JSONObject();
        String action = params.optString("action", "add").trim().toLowerCase(Locale.ROOT);
        if (action.isEmpty()) action = "add";
        NotepadStore store = NotepadStore.getInstance(ctx);
        LocalDate today = LocalDate.now(PARIS);

        switch (action) {
            case "list":
            case "show":
            case "today":
            case "aujourdhui":
            case "aujourd'hui":
                cb.onSuccessAndExit(ToolResult.text(formatDays(store, today, 1, true)));
                return;
            case "tomorrow":
            case "demain":
                cb.onSuccessAndExit(ToolResult.text(formatDays(store, today.plusDays(1), 1, false)));
                return;
            case "week":
            case "semaine":
            case "open":
                cb.onSuccessAndExit(ToolResult.text(formatDays(store, today, 7, true)));
                return;
            case "delete":
            case "remove": {
                String title = firstNonEmpty(params, "title", "titre", "name", "text");
                if (TextUtils.isEmpty(title)) {
                    cb.onError("Dis-moi ce que je retire de l'agenda.");
                    return;
                }
                int removed = store.removeContaining(title);
                if (removed > 0) cb.onSuccessAndExit(ToolResult.text("C'est retiré : " + title + "."));
                else cb.onError("Je n'ai rien trouvé pour « " + title + " ».");
                return;
            }
            case "add":
            case "create":
            case "set":
                executeAdd(store, params, cb);
                return;
            default:
                cb.onError("Action agenda inconnue. Utilise add, today, tomorrow, week, delete.");
        }
    }

    private void executeAdd(NotepadStore store, JSONObject params, ToolCallback cb) {
        String title = firstNonEmpty(params, "title", "titre", "name", "sujet", "text");
        if (TextUtils.isEmpty(title)) {
            cb.onError("Qu'est-ce que je note dans l'agenda ?");
            return;
        }
        String location = firstNonEmpty(params, "location", "lieu", "place");
        String text = location.isEmpty() || title.toLowerCase(Locale.FRENCH)
                .contains(location.toLowerCase(Locale.FRENCH))
                ? title : title + " (" + location + ")";

        String startRaw = firstNonEmpty(params, "start", "debut", "début", "when", "datetime");
        if (startRaw.isEmpty()) {
            startRaw = (params.optString("date", "") + " " + params.optString("time", "")).trim();
        }

        String due = LocalDate.now(PARIS).toString();
        long reminderAt = 0;
        if (!startRaw.isEmpty()) {
            long ms = FrenchDateTimeParser.parseToEpochMs(startRaw);
            if (ms > 0) {
                LocalDateTime dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), PARIS);
                due = dt.toLocalDate().toString();
                if (hasTime(startRaw) && ms > System.currentTimeMillis()) reminderAt = ms;
            }
        }
        NotepadDateHelper.ReminderResolution res = new NotepadDateHelper.ReminderResolution(
                due, reminderAt, false, "");
        NotepadStore.Item item = store.add(text, due, reminderAt, "", res);
        if (item == null) {
            cb.onError("Je n'ai pas pu l'ajouter.");
            return;
        }
        StringBuilder reply = new StringBuilder("C'est dans l'agenda : ").append(text).append(", ")
                .append(NotepadDateHelper.formatDateLabel(due));
        if (reminderAt > 0) {
            LocalDateTime dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(reminderAt), PARIS);
            reply.append(" à ").append(hourLabel(dt)).append(" — je te le rappelle");
        }
        cb.onSuccessAndExit(ToolResult.text(reply.append(".").toString()));
    }

    /**
     * Liste lisible des éléments du bloc-notes sur {@code days} jours à partir de {@code from}.
     * {@code withLate} : ajoute ce qui reste d'avant (pas fait).
     */
    public static String formatDays(NotepadStore store, LocalDate from, int days, boolean withLate) {
        List<NotepadStore.Item> active = store.getActiveItems();
        StringBuilder sb = new StringBuilder();
        int total = 0;
        for (int i = 0; i < days; i++) {
            String day = from.plusDays(i).toString();
            List<NotepadStore.Item> list = new ArrayList<>();
            for (NotepadStore.Item it : active) {
                if (day.equals(dueOf(it))) list.add(it);
            }
            if (list.isEmpty()) continue;
            list.sort((a, b) -> Long.compare(timeKey(a), timeKey(b)));
            if (sb.length() > 0) sb.append('\n');
            sb.append(capitalize(NotepadDateHelper.formatDateLabel(day))).append(" : ");
            appendItems(sb, list);
            total += list.size();
        }
        List<NotepadStore.Item> late = new ArrayList<>();
        if (withLate) {
            String first = from.toString();
            for (NotepadStore.Item it : active) {
                String d = dueOf(it);
                if (!d.isEmpty() && d.compareTo(first) < 0) late.add(it);
            }
        }
        if (!late.isEmpty()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append("Pas encore fait d'avant : ");
            appendItems(sb, late);
        }
        if (total == 0 && late.isEmpty()) {
            String label = days > 1 ? "cette semaine"
                    : NotepadDateHelper.formatDateLabel(from.toString());
            return "Rien de prévu " + label + ".";
        }
        return sb.toString();
    }

    private static void appendItems(StringBuilder sb, List<NotepadStore.Item> list) {
        for (int i = 0; i < list.size(); i++) {
            NotepadStore.Item it = list.get(i);
            if (i > 0) sb.append(" ; ");
            if (it.reminderAt > 0) {
                LocalDateTime dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(it.reminderAt), PARIS);
                sb.append(hourLabel(dt)).append(" ");
            }
            sb.append(it.text);
        }
        sb.append('.');
    }

    /** Date de rangement : l'échéance, sinon le jour du rappel, sinon le jour de création. */
    private static String dueOf(NotepadStore.Item it) {
        if (!it.dueDate.isEmpty()) return it.dueDate;
        if (it.reminderAt > 0) {
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(it.reminderAt), PARIS)
                    .toLocalDate().toString();
        }
        String c = it.created == null ? "" : it.created.trim();
        return c.length() >= 10 && c.charAt(4) == '-' ? c.substring(0, 10) : "";
    }

    private static long timeKey(NotepadStore.Item it) {
        return it.reminderAt > 0 ? it.reminderAt : Long.MAX_VALUE;
    }

    private static String hourLabel(LocalDateTime dt) {
        return dt.getMinute() == 0
                ? dt.getHour() + "h"
                : dt.format(DateTimeFormatter.ofPattern("H'h'mm", Locale.FRENCH));
    }

    private static boolean hasTime(String raw) {
        return raw.matches("(?is).*\\d{1,2}\\s*(h|:|heure).*") || raw.contains("midi")
                || raw.contains("minuit");
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return "";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
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
