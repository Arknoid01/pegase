package com.pegasuscorp.orbe.tools.device;

import com.pegasuscorp.orbe.tools.ToolTag;

import com.pegasuscorp.orbe.tools.ToolResult;

import com.pegasuscorp.orbe.tools.Tool;
import com.pegasuscorp.orbe.tools.ToolCallback;

import android.content.Context;
import android.content.Intent;
import android.provider.AlarmClock;
import android.text.TextUtils;

import org.json.JSONObject;

/**
 * Réveil Android. Ajouter un réveil : OK. Lister : limité par Android → ouverture de l'Horloge.
 */
public final class AlarmTool implements Tool {

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
        return "alarm(action:\"add\"|\"list\", hour?:int, minute?:int, label?:str, message?:str)"
                + " — Réveil (sonne une fois). ≠ agenda (événement calendrier). "
                + "action add (défaut) ✅ hour 0–23, minute optionnelle. "
                + "label (ou message) : texte affiché dans l'Horloge — "
                + "TOUJOURS extraire du contexte si dispo "
                + "(« alarme à 10h pour chauffer les plats » → "
                + "alarm(10, 0, label:\"Chauffer les plats\")). "
                + "action list : ouvre l'app Horloge.";
    }

    @Override
    public void execute(Context ctx, JSONObject params, ToolCallback cb) {
        String action = params.optString("action", "add").trim().toLowerCase();
        if (action.isEmpty()) action = "add";

        if ("list".equals(action) || "show".equals(action) || "open".equals(action)) {
            openClockAlarms(ctx, cb);
            return;
        }

        if (!"add".equals(action) && !"set".equals(action)) {
            cb.onError("Action réveil inconnue. Utilise add (programmer) ou list (ouvrir Horloge).");
            return;
        }

        int hour = params.optInt("hour", -1);
        int minute = params.optInt("minute", 0);
        if (hour < 0 || hour > 23) {
            cb.onError("Précise l'heure du réveil (0 à 23 h).");
            return;
        }
        if (minute < 0 || minute > 59) minute = 0;

        String label = params.optString("label", "").trim();
        if (label.isEmpty()) label = params.optString("message", "").trim();

        Intent intent = new Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true);
        if (!TextUtils.isEmpty(label)) {
            intent.putExtra(AlarmClock.EXTRA_MESSAGE, label);
        }
        if (intent.resolveActivity(ctx.getPackageManager()) != null) {
            ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            String spoken = "Alarme " + formatTime(hour, minute);
            if (!TextUtils.isEmpty(label)) {
                spoken += " — " + label;
            }
            spoken += " ✅";
            cb.onSuccessAndExit(ToolResult.text(spoken));
        } else {
            cb.onError("Aucune app réveil sur ce téléphone.");
        }
    }

    private static void openClockAlarms(Context ctx, ToolCallback cb) {
        Intent show = new Intent(AlarmClock.ACTION_SHOW_ALARMS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (show.resolveActivity(ctx.getPackageManager()) != null) {
            ctx.startActivity(show);
            cb.onSuccessAndExit(ToolResult.text(
                    "Android ne me laisse pas lire ta liste de réveils. "
                            + "J'ouvre l'Horloge pour que tu les voies."));
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
            cb.onSuccessAndExit(ToolResult.text(
                    "Je ne peux pas lister les réveils depuis ici — "
                            + "j'ouvre l'app Horloge."));
            return;
        }
        cb.onError("Aucune app Horloge trouvée. Ouvre-la manuellement pour voir tes réveils.");
    }

    private static String formatTime(int hour, int minute) {
        return String.format("%02dh%02d", hour, minute);
    }
}
