package com.pegasuscorp.orbe.tools.device;

import com.pegasuscorp.orbe.tools.ToolTag;

import com.pegasuscorp.orbe.tools.ToolResult;

import com.pegasuscorp.orbe.tools.Tool;
import com.pegasuscorp.orbe.tools.ToolCallback;

import android.content.Context;
import android.content.Intent;
import android.provider.AlarmClock;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Réveil Android — création avec libellé, jours récurrents, historique local Pégase.
 * Lister côté système : limité → ouverture Horloge + registre local.
 */
public final class AlarmTool implements Tool {

    private static final Pattern TIME = Pattern.compile(
            "(?i)(?:à|a)?\\s*(\\d{1,2})\\s*(?:h|:)\\s*(\\d{0,2})");

    @Override
    public String id() {
        return "alarm";
    }

    @Override
    public ToolTag tag() {
        return ToolTag.ALARM;
    }

    @Override
    public String description() {
        return "alarm(action:\"add\"|\"list\"|\"recent\", hour?:int, minute?:int, time?:str, "
                + "label?:str, message?:str, days?:int[]) — Réveil Horloge. "
                + "time « 7h30 » si hour absent ; days 1=lun…7=dim ; list/recent.";
    }

    @Override
    public void execute(Context ctx, JSONObject params, ToolCallback cb) {
        String action = params.optString("action", "add").trim().toLowerCase(Locale.ROOT);
        if (action.isEmpty()) action = "add";

        if ("list".equals(action) || "show".equals(action) || "open".equals(action)) {
            openClockAlarms(ctx, cb);
            return;
        }
        if ("recent".equals(action) || "history".equals(action)) {
            cb.onSuccessAndExit(ToolResult.text(
                    UtilityScheduleStore.get(ctx).formatRecentAlarms()));
            return;
        }

        if (!"add".equals(action) && !"set".equals(action)) {
            cb.onError("Action réveil inconnue. Utilise add, list ou recent.");
            return;
        }

        int hour = params.optInt("hour", -1);
        int minute = params.optInt("minute", 0);
        if (hour < 0) {
            String timeRaw = firstNonEmpty(params, "time", "heure", "when");
            int[] parsed = parseTime(timeRaw);
            if (parsed != null) {
                hour = parsed[0];
                minute = parsed[1];
            }
        }
        if (hour < 0 || hour > 23) {
            cb.onError("Précise l'heure du réveil (hour ou time « 7h30 »).");
            return;
        }
        if (minute < 0 || minute > 59) minute = 0;

        String label = firstNonEmpty(params, "label", "message", "name", "titre");

        Intent intent = new Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true);
        if (!TextUtils.isEmpty(label)) {
            intent.putExtra(AlarmClock.EXTRA_MESSAGE, label);
        }
        ArrayList<Integer> days = parseDays(params);
        if (days != null && !days.isEmpty()) {
            intent.putExtra(AlarmClock.EXTRA_DAYS, days);
        }
        if (intent.resolveActivity(ctx.getPackageManager()) != null) {
            ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            UtilityScheduleStore.get(ctx).recordAlarm(hour, minute, label);
            String spoken = "Alarme " + formatTime(hour, minute);
            if (!TextUtils.isEmpty(label)) spoken += " — " + label;
            if (days != null && !days.isEmpty()) spoken += " (répétée)";
            spoken += " ✅";
            cb.onSuccessAndExit(ToolResult.text(spoken));
        } else {
            cb.onError("Aucune app réveil sur ce téléphone.");
        }
    }

    static int[] parseTime(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        Matcher m = TIME.matcher(raw.trim());
        if (!m.find()) {
            m = Pattern.compile("(?i)(\\d{1,2})\\s*h(?:\\s*(\\d{1,2}))?").matcher(raw.trim());
            if (!m.find()) return null;
        }
        int hour = Integer.parseInt(m.group(1));
        int minute = 0;
        if (m.group(2) != null && !m.group(2).isEmpty()) {
            minute = Integer.parseInt(m.group(2));
        }
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) return null;
        return new int[]{hour, minute};
    }

    private static ArrayList<Integer> parseDays(JSONObject params) {
        if (params == null || !params.has("days")) return null;
        ArrayList<Integer> out = new ArrayList<>();
        JSONArray arr = params.optJSONArray("days");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                int d = arr.optInt(i, -1);
                if (d >= 1 && d <= 7) out.add(d);
            }
            return out;
        }
        String raw = params.optString("days", "").trim();
        if (raw.isEmpty()) return out;
        for (String part : raw.split("[,;\\s]+")) {
            if (part.isEmpty()) continue;
            try {
                int d = Integer.parseInt(part);
                if (d >= 1 && d <= 7) out.add(d);
            } catch (NumberFormatException ignored) {}
        }
        return out;
    }

    private static void openClockAlarms(Context ctx, ToolCallback cb) {
        Intent show = new Intent(AlarmClock.ACTION_SHOW_ALARMS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (show.resolveActivity(ctx.getPackageManager()) != null) {
            ctx.startActivity(show);
            String msg = UtilityScheduleStore.get(ctx).formatRecentAlarms();
            cb.onSuccessAndExit(ToolResult.text(
                    "J'ouvre l'Horloge.\n" + msg));
            return;
        }
        Intent fallback = ctx.getPackageManager()
                .getLaunchIntentForPackage("com.google.android.deskclock");
        if (fallback == null) {
            fallback = ctx.getPackageManager()
                    .getLaunchIntentForPackage("com.android.deskclock");
        }
        if (fallback != null) {
            ctx.startActivity(fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            cb.onSuccessAndExit(ToolResult.text("J'ouvre l'app Horloge."));
            return;
        }
        cb.onError("Aucune app Horloge trouvée.");
    }

    private static String firstNonEmpty(JSONObject params, String... keys) {
        for (String k : keys) {
            String v = params.optString(k, "").trim();
            if (!v.isEmpty()) return v;
        }
        return "";
    }

    private static String formatTime(int hour, int minute) {
        return String.format(Locale.ROOT, "%02dh%02d", hour, minute);
    }
}
