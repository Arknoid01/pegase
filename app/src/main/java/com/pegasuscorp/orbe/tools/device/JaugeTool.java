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
        return "batterie(action:\"etat\"|\"conso\"|\"etat_conso\"|\"recharges\"|\"telephone\"|\"longue_journee_on\"|\"longue_journee_off\"|\"ouvrir\", "
                + "sujet?:\"reveils\"|\"signal\"|\"taches\"|\"temperatures\"|\"chargeur\"|\"donnees\"|\"ecran\"|"
                + "\"notifications\"|\"vie_privee\"|\"stockage\"|\"memoire\") — "
                + "batterie détaillée (app Jauge). etat=niveau, rythme %/h, temps restant, usure ; "
                + "conso=quelles apps vident la batterie, GPS, alertes, dernière nuit ; "
                + "longue_journee_on/off=mode économie (économiseur, luminosité, apps bridées) ; "
                + "recharges=fiches des dernières décharges (entre deux recharges : durée, %/h, apps) ; "
                + "telephone=autres mesures du téléphone selon sujet (réveils par app, signal mobile, "
                + "tâches de fond, températures, chargeur, données mobiles/Wi-Fi par app, temps d'écran, "
                + "notifications par app, vie privée = qui a utilisé micro/caméra/position, stockage, mémoire) ; "
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
        if (action.equals("etat_conso")) {
            // « combien il reste ET quelles applis consomment » : les deux mesures en un appel
            Context app = ctx.getApplicationContext();
            Handler main = new Handler(Looper.getMainLooper());
            new Thread(() -> {
                String etat = query(app, "etat", null);
                String conso = query(app, "conso", null);
                main.post(() -> {
                    if (etat == null && conso == null) {
                        cb.onError("Jauge ne répond pas.");
                        return;
                    }
                    String shown = (etat != null ? firstLine(etat) : "")
                            + (conso != null ? (etat != null ? "\n" : "") + firstLine(conso) : "");
                    cb.onSuccess(ToolResult.text(shown, "Consigne de réponse : donne le niveau et le "
                            + "temps restant, puis ce qui vide la batterie en commençant par le plus gros "
                            + "consommateur, en 3 phrases maximum.\n"
                            + (etat != null ? etat + "\n" : "") + (conso != null ? conso : "")));
                });
            }, "jauge-tool").start();
            return;
        }
        if (action.startsWith("rech") || action.contains("cycle")) {
            method = "recharges";
            arg = null;
            consigne = "Consigne de réponse : résume la dernière décharge (durée, rythme, ce qui a consommé) "
                    + "et compare-la aux précédentes si utile, en 2-3 phrases.\n";
        } else if (action.startsWith("tel") || action.startsWith("tél")) {
            method = "telephone";
            arg = params.optString("sujet", "").trim().toLowerCase(Locale.ROOT);
            consigne = "Consigne de réponse : réponds à la question avec ces mesures, en 2-3 phrases, "
                    + "en citant les apps ou chiffres marquants ; dis si quelque chose paraît anormal.\n";
        } else if (action.contains("conso") || action.contains("vide") || action.contains("app")) {
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
