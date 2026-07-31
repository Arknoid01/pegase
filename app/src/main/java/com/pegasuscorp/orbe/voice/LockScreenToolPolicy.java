package com.pegasuscorp.orbe.voice;

import org.json.JSONObject;

/**
 * Outils autorisés à l'écran verrouillé (v3 P1) — pas d'ouverture d'app tierce.
 */
public final class LockScreenToolPolicy {

    public static final String AGENDA_DENIED_NOTIF =
            "Action agenda refusée — déverrouille ou confirme ta voix.";

    private LockScreenToolPolicy() {}

    public static boolean isWhitelistedOnLockScreen(String intentHint, String toolJson) {
        String tool = normalizeToolId(intentHint, toolJson);
        if (tool == null) return false;
        switch (tool) {
            case "calculator":
            case "calcul":
            case "timer":
            case "minuteur":
            case "alarm":
            case "réveil":
            case "reveil":
            case "agenda":
            case "calendar":
            case "calendrier":
                return true;
            default:
                return false;
        }
    }

    public static boolean requiresSpeakerVerifyOnLock(String intentHint, String toolJson) {
        String tool = normalizeToolId(intentHint, toolJson);
        return isAgendaTool(tool);
    }

    private static boolean isAgendaTool(String tool) {
        return "agenda".equals(tool) || "calendar".equals(tool) || "calendrier".equals(tool);
    }

    private static String normalizeToolId(String intentHint, String toolJson) {
        if (intentHint != null && !intentHint.trim().isEmpty()) {
            return intentHint.trim().toLowerCase(java.util.Locale.ROOT);
        }
        if (toolJson == null || toolJson.trim().isEmpty()) return null;
        try {
            JSONObject o = new JSONObject(toolJson.trim());
            if (o.has("tool")) {
                return o.optString("tool", "").trim().toLowerCase(java.util.Locale.ROOT);
            }
            java.util.Iterator<String> keys = o.keys();
            if (keys.hasNext()) {
                return keys.next().trim().toLowerCase(java.util.Locale.ROOT);
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
