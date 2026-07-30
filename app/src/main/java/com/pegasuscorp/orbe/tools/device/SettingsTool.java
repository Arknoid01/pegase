package com.pegasuscorp.orbe.tools.device;

import com.pegasuscorp.orbe.tools.ToolTag;

import com.pegasuscorp.orbe.tools.ToolResult;

import com.pegasuscorp.orbe.tools.Tool;
import com.pegasuscorp.orbe.tools.ToolCallback;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONObject;

/**
 * Ouvre les panneaux Réglages système (avion, hotspot, luminosité, son).
 * Pas de bascule silencieuse des modes restreints — l'utilisateur confirme dans Android.
 */
public final class SettingsTool implements Tool {

    @Override
    public String id() {
        return "settings";
    }

    @Override
    public ToolTag tag() {
        return ToolTag.SETTINGS;
    }

    @Override
    public String description() {
        return "settings(panel:\"airplane\"|\"hotspot\"|\"brightness\"|\"sound\"|\"display\"|\"wireless\") — "
                + "Ouvre le panneau Réglages Android correspondant. "
                + "Exemple : {\"tool\":\"settings\",\"params\":{\"panel\":\"airplane\"}}.";
    }

    @Override
    public void execute(Context ctx, JSONObject params, ToolCallback cb) {
        String panel = params != null ? params.optString("panel", "").trim().toLowerCase() : "";
        if (panel.isEmpty() && params != null) {
            panel = params.optString("action", "").trim().toLowerCase();
        }
        if (panel.isEmpty()) {
            cb.onError("Quel panneau ? avion, hotspot, luminosité ou son.");
            return;
        }

        Intent intent = intentFor(panel);
        if (intent == null) {
            cb.onError("Panneau inconnu. Essaie : airplane, hotspot, brightness, sound.");
            return;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            if (intent.resolveActivity(ctx.getPackageManager()) == null
                    && !"hotspot".equals(normalize(panel))) {
                cb.onError("Ce panneau n'est pas disponible sur cet appareil.");
                return;
            }
            ctx.startActivity(intent);
            cb.onSuccessAndExit(ToolResult.text(spokenFor(normalize(panel))));
        } catch (Exception e) {
            // Hotspot : certains constructeurs n'exposent pas TETHER_SETTINGS
            if ("hotspot".equals(normalize(panel))) {
                try {
                    Intent fallback = new Intent(Settings.ACTION_WIRELESS_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(fallback);
                    cb.onSuccessAndExit(ToolResult.text(
                            "Voici les réglages réseau — cherche le partage de connexion."));
                    return;
                } catch (Exception ignored) {}
            }
            cb.onError("Impossible d'ouvrir les réglages : "
                    + (e.getMessage() != null ? e.getMessage() : "erreur"));
        }
    }

    private static String normalize(String panel) {
        if (panel == null) return "";
        String p = panel.trim().toLowerCase();
        if (p.contains("avion") || p.equals("flight") || p.equals("airplane")
                || p.equals("aeroplane")) {
            return "airplane";
        }
        if (p.contains("hotspot") || p.contains("partage") || p.contains("tether")
                || p.contains("modem")) {
            return "hotspot";
        }
        if (p.contains("lumin") || p.equals("brightness") || p.equals("bright")) {
            return "brightness";
        }
        if (p.contains("son") || p.equals("sound") || p.equals("audio")
                || p.contains("media") || p.contains("ringtone")) {
            return "sound";
        }
        if (p.contains("affich") || p.equals("display") || p.equals("ecran")) {
            return "display";
        }
        if (p.contains("sans fil") || p.equals("wireless") || p.equals("reseau")) {
            return "wireless";
        }
        return p;
    }

    private static Intent intentFor(String panel) {
        switch (normalize(panel)) {
            case "airplane":
                return new Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS);
            case "hotspot":
                Intent tether = new Intent("android.settings.TETHER_SETTINGS");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // Certains OEM : panneau rapide Wi‑Fi / réseau
                    return tether;
                }
                return tether;
            case "brightness":
            case "display":
                return new Intent(Settings.ACTION_DISPLAY_SETTINGS);
            case "sound":
                return new Intent(Settings.ACTION_SOUND_SETTINGS);
            case "wireless":
                return new Intent(Settings.ACTION_WIRELESS_SETTINGS);
            default:
                return null;
        }
    }

    private static String spokenFor(String panel) {
        switch (panel) {
            case "airplane":
                return "Voici le mode avion.";
            case "hotspot":
                return "Voici le partage de connexion.";
            case "brightness":
            case "display":
                return "Voici la luminosité / l'affichage.";
            case "sound":
                return "Voici les réglages son.";
            case "wireless":
                return "Voici les réglages réseau.";
            default:
                return "Voici les réglages.";
        }
    }
}
