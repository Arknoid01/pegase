package com.pegasuscorp.orbe.tools.copilot;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Repli automatique : échec {@code ui_action.steps} → {@code ui_loop}.
 */
public final class StepsToLoopFallback {

    private StepsToLoopFallback() {}

    /**
     * Erreurs récupérables (libellé faux, cookie, champ absent…) —
     * pas les annulations user ni les préconditions mortes.
     */
    public static boolean shouldFallback(String error) {
        if (error == null || error.trim().isEmpty()) return false;
        String e = error.toLowerCase();
        if (e.contains("annul")) return false;
        if (e.contains("whitelist") || e.contains("pas autoris")) return false;
        if (e.contains("accessibilité pas") || e.contains("service d'accessibilité")) {
            return false;
        }
        if (e.contains("séquence vide") || e.contains("trop d'étapes")) return false;
        if (e.contains("indisponible") && e.contains("boucle")) return false;
        // Étape N / introuvable / fantôme / open fail → oui
        return e.contains("étape")
                || e.contains("trouve pas")
                || e.contains("introuvable")
                || e.contains("n'a pas abouti")
                || e.contains("pas abouti")
                || e.contains("délai")
                || e.contains("timeout")
                || e.contains("premier plan")
                || e.contains("impossible")
                || e.contains("ouverte")
                || e.contains("ouvrir")
                || e.contains("cliquable")
                || e.contains("saisir")
                || e.contains("défiler")
                || e.contains("action inconnue");
    }

    /** Objectif lisible pour la boucle, dérivé du plan steps. */
    public static String goalFromSteps(JSONArray steps) {
        if (steps == null || steps.length() == 0) {
            return "Continuer l'action à l'écran";
        }
        StringBuilder sb = new StringBuilder("Réalise ce plan UI (adapte si l'écran diffère) : ");
        for (int i = 0; i < steps.length(); i++) {
            JSONObject step = steps.optJSONObject(i);
            if (step == null) continue;
            if (i > 0) sb.append(" ; ");
            sb.append(describeStep(step));
        }
        return sb.toString();
    }

    public static String traceFromFailure(JSONArray steps, String error) {
        StringBuilder sb = new StringBuilder();
        sb.append("Plan steps initial :\n");
        if (steps != null) {
            for (int i = 0; i < steps.length(); i++) {
                JSONObject step = steps.optJSONObject(i);
                if (step == null) continue;
                sb.append(i + 1).append(". ").append(describeStep(step)).append('\n');
            }
        }
        sb.append("Échec : ").append(error != null ? error : "?");
        return sb.toString();
    }

    static String describeStep(JSONObject step) {
        if (step == null) return "?";
        String action = step.optString("action", "").trim().toLowerCase();
        String target = firstNonEmpty(
                step.optString("target", ""),
                step.optString("name", ""),
                step.optString("app", ""));
        String value = step.optString("value", step.optString("text_value", ""));
        String dir = step.optString("direction", "");
        switch (action) {
            case "open":
            case "launch":
            case "open_app":
                return "ouvrir " + (target.isEmpty() ? "l'app" : target);
            case "click":
            case "tap":
                return "cliquer « " + (target.isEmpty() ? "?" : target) + " »";
            case "type":
            case "set_text":
                if (!target.isEmpty() && !value.isEmpty()) {
                    return "taper « " + value + " » dans « " + target + " »";
                }
                if (!value.isEmpty()) return "taper « " + value + " »";
                return "saisir du texte";
            case "scroll":
                return "défiler " + (dir.isEmpty() ? "down" : dir);
            case "back":
            case "retour":
            case "go_back":
                return "retour arrière";
            default:
                return action.isEmpty() ? "?" : action;
        }
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) return "";
        for (String v : values) {
            if (!TextUtils.isEmpty(v) && !v.trim().isEmpty()) return v.trim();
        }
        return "";
    }
}
