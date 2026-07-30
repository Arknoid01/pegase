package com.pegasuscorp.orbe.intentions;

/**
 * Identifiants stables des intentions V1 — valider avant toute action PendingIntent.
 */
public final class IntentionIds {

    public static final String BATTERY_LOW = "battery_low";
    public static final String WORK_WIFI = "work_wifi";
    public static final String BRIEF_READY = "brief_ready";
    public static final String ORION_RETRY = "orion_retry";
    public static final String DRIVE_BT = "drive_bt";
    public static final String F1_DEBRIEF_READY = "f1_debrief_ready";
    public static final String F1_NEWS = "f1_news";
    public static final String F1_LIVE = "f1_live";

    public static final String ACTION_ACCEPT = "accept";
    public static final String ACTION_SNOOZE = "snooze";
    public static final String ACTION_NEVER = "never";
    public static final String ACTION_REMIND = "remind";
    public static final String ACTION_IGNORE_TODAY = "ignore_today";

    private IntentionIds() {}

    public static boolean isValid(String id) {
        if (BATTERY_LOW.equals(id) || WORK_WIFI.equals(id) || BRIEF_READY.equals(id)
                || ORION_RETRY.equals(id) || DRIVE_BT.equals(id)
                || F1_DEBRIEF_READY.equals(id) || F1_NEWS.equals(id)
                || F1_LIVE.equals(id)) {
            return true;
        }
        if (id == null) return false;
        if (id.startsWith("life:") && id.length() > 5) return true;
        return id.startsWith("calendar:") && id.length() > 9;
    }

    public static boolean isValidAction(String action) {
        return ACTION_ACCEPT.equals(action)
                || ACTION_SNOOZE.equals(action)
                || ACTION_NEVER.equals(action)
                || ACTION_REMIND.equals(action)
                || ACTION_IGNORE_TODAY.equals(action);
    }

    public static String displayName(String id) {
        if (BATTERY_LOW.equals(id)) return "Batterie faible";
        if (WORK_WIFI.equals(id)) return "Mode travail";
        if (BRIEF_READY.equals(id)) return "Brief prêt";
        if (ORION_RETRY.equals(id)) return "Relancer Orion";
        if (DRIVE_BT.equals(id)) return "Mode conduite";
        if (F1_DEBRIEF_READY.equals(id)) return "Débrief F1";
        if (F1_NEWS.equals(id)) return "Actu F1";
        if (F1_LIVE.equals(id)) return "Live F1";
        if (id != null && id.startsWith("life:")) return "Rythme de vie";
        if (id != null && id.startsWith("calendar:")) return "RDV bientôt";
        return id == null ? "" : id;
    }
}
