package com.pegasuscorp.orbe.f1companion;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * Détecte un nouveau Grand Prix et gère l'offre de débrief (Intentions).
 */
public final class F1DebriefOffer {

    private static final String TAG = "F1DebriefOffer";
    private static final String PREFS = "f1_debrief_offer";
    private static final String KEY_ACK_SESSION = "ack_session_key";
    private static final String KEY_PENDING_SESSION = "pending_session_key";
    private static final String KEY_PENDING_LABEL = "pending_event_label";

    private F1DebriefOffer() {}

    /**
     * Rafraîchit la fiche si besoin ; retourne un snapshot à proposer en notif,
     * ou null si rien de nouveau / déjà traité.
     */
    public static WeekendSnapshot pollForNewRace(Context ctx) {
        if (ctx == null) return null;
        try {
            WeekendSnapshot snap = F1CompanionStore.ensureFresh(ctx, false);
            if (snap == null || !snap.hasRaceResults() || snap.sessionKey <= 0) return null;

            SharedPreferences p = prefs(ctx);
            int ack = p.getInt(KEY_ACK_SESSION, 0);
            int sk = snap.sessionKey;

            if (sk == ack) return null;

            // Premier run : mémoriser silencieusement le GP actuel
            if (ack == 0) {
                p.edit().putInt(KEY_ACK_SESSION, sk).apply();
                Log.i(TAG, "Seed ack session " + sk + " (" + snap.event + ")");
                return null;
            }

            // Nouveau GP par rapport au dernier acquitté
            setPending(ctx, sk, snap.event);
            return snap;
        } catch (Exception e) {
            Log.w(TAG, "pollForNewRace", e);
            return null;
        }
    }

    public static void setPending(Context ctx, int sessionKey, String eventLabel) {
        prefs(ctx).edit()
                .putInt(KEY_PENDING_SESSION, sessionKey)
                .putString(KEY_PENDING_LABEL, eventLabel != null ? eventLabel : "")
                .apply();
    }

    public static int getPendingSessionKey(Context ctx) {
        return prefs(ctx).getInt(KEY_PENDING_SESSION, 0);
    }

    public static String getPendingLabel(Context ctx) {
        String s = prefs(ctx).getString(KEY_PENDING_LABEL, "");
        return s != null ? s : "";
    }

    /** En parler / Pas pour ce GP — n'offre plus ce session_key. */
    public static void acknowledgePending(Context ctx) {
        SharedPreferences p = prefs(ctx);
        int pending = p.getInt(KEY_PENDING_SESSION, 0);
        SharedPreferences.Editor ed = p.edit().remove(KEY_PENDING_SESSION).remove(KEY_PENDING_LABEL);
        if (pending > 0) ed.putInt(KEY_ACK_SESSION, pending);
        ed.apply();
    }

    /** Tests. */
    public static void resetForTests(Context ctx) {
        prefs(ctx).edit().clear().apply();
    }

    public static void seedAckForTests(Context ctx, int sessionKey) {
        prefs(ctx).edit().putInt(KEY_ACK_SESSION, sessionKey).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
