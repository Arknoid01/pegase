package com.pegasuscorp.orbe.tools.device;

import com.pegasuscorp.orbe.tools.ToolTag;

import com.pegasuscorp.orbe.tools.ToolResult;

import com.pegasuscorp.orbe.tools.Tool;
import com.pegasuscorp.orbe.tools.ToolCallback;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Infos locales : batterie, heure, date. */
public final class DeviceTool implements Tool {

    @Override
    public String id() {
        return "device";
    }

    @Override
    public ToolTag tag() {
        return ToolTag.DEVICE;
    }

    @Override
    public String description() {
        return "device(action:string) — Infos téléphone. "
                + "action obligatoire : battery (niveau de charge), time (heure), date (jour). "
                + "Pour « batterie / charge » → action=battery. "
                + "Pour « quelle heure » → action=time. "
                + "Pour « quelle date / quel jour » → action=date.";
    }

    @Override
    public void execute(Context ctx, JSONObject params, ToolCallback cb) {
        String action = resolveAction(params);
        switch (action) {
            case "battery":
                cb.onSuccess(ToolResult.text(readBattery(ctx)));
                break;
            case "date":
                cb.onSuccess(ToolResult.text("Nous sommes le "
                        + new SimpleDateFormat("EEEE d MMMM yyyy", Locale.FRENCH)
                                .format(new Date()) + "."));
                break;
            case "time":
                cb.onSuccess(ToolResult.text("Il est "
                        + new SimpleDateFormat("HH'h'mm", Locale.FRENCH)
                                .format(new Date()) + "."));
                break;
            default:
                cb.onError("Précise action : battery, time ou date.");
        }
    }

    /** Normalise action / alias FR ; vide si inconnu (pas de défaut « time » silencieux). */
    static String resolveAction(JSONObject params) {
        if (params == null) return "";
        String raw = params.optString("action", "").trim().toLowerCase(Locale.ROOT);
        if (raw.isEmpty()) {
            raw = params.optString("query", "").trim().toLowerCase(Locale.ROOT);
        }
        if (raw.isEmpty()) {
            raw = params.optString("type", "").trim().toLowerCase(Locale.ROOT);
        }
        if (raw.contains("batter") || raw.contains("charge") || "battery".equals(raw)) {
            return "battery";
        }
        if (raw.contains("date") || raw.contains("jour") || "date".equals(raw)) {
            return "date";
        }
        if (raw.contains("heure") || raw.contains("time") || "time".equals(raw)
                || "clock".equals(raw)) {
            return "time";
        }
        return raw;
    }

    private static String readBattery(Context ctx) {
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent battery = ctx.registerReceiver(null, filter);
        if (battery == null) return "Je n'ai pas pu lire la batterie.";
        int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int pct = scale > 0 ? (level * 100 / scale) : level;
        int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
        if (charging) {
            return "Batterie à " + pct + " pour cent, en charge.";
        }
        return "Batterie à " + pct + " pour cent.";
    }
}
