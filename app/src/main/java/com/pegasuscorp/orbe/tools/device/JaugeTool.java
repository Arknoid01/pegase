package com.pegasuscorp.orbe.tools.device;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import com.pegasuscorp.orbe.tools.Tool;
import com.pegasuscorp.orbe.tools.ToolCallback;
import com.pegasuscorp.orbe.tools.ToolResult;
import com.pegasuscorp.orbe.tools.ToolTag;

import org.json.JSONObject;

import java.util.Locale;

/**
 * Batterie détaillée via l'app Jauge (com.pegase.jauge) : rythme, temps restant, usure,
 * qui consomme, mode longue journée. Lecture par un ContentProvider protégé par signature.
 */
public final class JaugeTool implements Tool {

    public static final String PACKAGE = "com.pegase.jauge";
    private static final Uri URI = Uri.parse("content://com.pegase.jauge.stats");

    @Override
    public String id() {
        return "batterie";
    }

    @Override
    public ToolTag tag() {
        return ToolTag.DEVICE;
    }

    @Override
    public String description() {
        return "batterie(action:\"etat\"|\"conso\"|\"longue_journee_on\"|\"longue_journee_off\"|\"ouvrir\") — "
                + "batterie détaillée (app Jauge). etat=niveau, rythme %/h, temps restant, usure ; "
                + "conso=quelles apps vident la batterie, GPS, alertes, dernière nuit ; "
                + "longue_journee_on/off=mode économie (économiseur, luminosité, apps bridées) ; "
                + "ouvrir=ouvrir Jauge.";
    }

    /** Jauge installée et guichet joignable. */
    public static boolean available(Context ctx) {
        try {
            return ctx.getPackageManager().resolveContentProvider("com.pegase.jauge.stats", 0) != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** Appel bloquant au guichet (à faire hors thread UI) ; null si indisponible. */
    public static String query(Context ctx, String method, String arg) {
        try {
            Bundle b = ctx.getContentResolver().call(URI, method, arg, null);
            return b != null ? b.getString("text") : null;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void execute(Context ctx, JSONObject params, ToolCallback cb) {
        String action = params != null
                ? params.optString("action", "etat").trim().toLowerCase(Locale.ROOT) : "etat";
        if (action.startsWith("ouvr") || action.equals("open")) {
            Intent launch = ctx.getPackageManager().getLaunchIntentForPackage(PACKAGE);
            if (launch == null) {
                cb.onSuccess(ToolResult.text("Jauge n'est pas installée."));
                return;
            }
            ctx.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            cb.onSuccessAndExit(ToolResult.text("J'ouvre Jauge."));
            return;
        }
        if (!available(ctx)) {
            cb.onSuccess(ToolResult.text("Jauge n'est pas installée : je ne vois que le pourcentage."));
            return;
        }
        final String method;
        final String arg;
        final String consigne;
        if (action.contains("conso") || action.contains("vide") || action.contains("app")) {
            method = "conso";
            arg = null;
            consigne = "Consigne de réponse : dis en 2-3 phrases ce qui vide la batterie, en commençant "
                    + "par le plus gros consommateur ; signale un GPS ou une alerte anormale s'il y en a ; "
                    + "propose une action simple si utile.\n";
        } else if (action.contains("longue") || action.contains("eco")) {
            method = "longue_journee";
            arg = action.endsWith("off") || action.contains("arret") ? "off" : "on";
            consigne = "Consigne de réponse : confirme en une phrase ce qui a été fait.\n";
        } else {
            method = "etat";
            arg = null;
            consigne = "Consigne de réponse : donne le niveau et le temps restant en une ou deux phrases ; "
                    + "ne cite l'usure que si on te la demande ou si elle est basse.\n";
        }
        Context app = ctx.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            String text = query(app, method, arg);
            main.post(() -> {
                if (text == null) cb.onError("Jauge ne répond pas.");
                else cb.onSuccess(ToolResult.text(firstLine(text), consigne + text));
            });
        }, "jauge-tool").start();
    }

    private static String firstLine(String t) {
        int i = t.indexOf('\n');
        return i > 0 ? t.substring(0, i) : t;
    }
}
