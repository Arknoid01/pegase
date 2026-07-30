package com.pegasuscorp.orbe.f1companion;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Poll live OpenF1 → événements rares (SC, VSC, pénalités équipes, fin de course).
 */
public final class F1LivePipeline {

    private static final String TAG = "F1LivePipeline";

    private F1LivePipeline() {}

    /**
     * @return événement à notifier, ou null
     */
    public static F1LiveEvent pollForOffer(Context ctx) {
        if (ctx == null) return null;
        Context app = ctx.getApplicationContext();
        try {
            if (!F1LiveStore.isEnabled(app)) return null;

            JSONObject session = OpenF1Service.findLiveRaceSession();
            if (session == null) {
                // Hors course : garder peu d'état
                return null;
            }
            int sk = session.optInt("session_key", 0);
            if (sk <= 0) return null;

            int prevSk = F1LiveStore.getSessionKey(app);
            if (prevSk != sk) {
                F1LiveStore.resetSessionState(app);
                F1LiveStore.setSessionKey(app, sk);
            }

            Map<Integer, OpenF1Service.DriverInfo> drivers =
                    OpenF1Service.loadDriversPublic(sk);
            List<FavoriteTeamsStore.TeamDef> favorites =
                    FavoriteTeamsStore.selectedTeams(app);

            List<JSONObject> rc = OpenF1Service.fetchRaceControl(sk);

            // Seed silencieux au premier contact avec cette session
            if (F1LiveStore.getSeededSession(app) != sk) {
                long maxMs = 0L;
                for (JSONObject o : rc) {
                    long t = OpenF1Service.parseIsoPublic(o.optString("date", ""));
                    if (t > maxMs) maxMs = t;
                }
                F1LiveStore.setLastRaceControlMs(app, maxMs);
                F1LiveStore.setSeededSession(app, sk);
                try {
                    Map<Integer, Integer> pos = F1LiveDetector.latestPositions(
                            OpenF1Service.fetchPositions(sk));
                    F1LiveStore.setPositions(app, pos);
                } catch (Exception ignored) {}
                Log.i(TAG, "Seed live session " + sk);
                return null;
            }

            long since = F1LiveStore.getLastRaceControlMs(app);
            List<F1LiveEvent> events = F1LiveDetector.fromRaceControl(
                    rc, drivers, favorites, since);

            // Avancer le curseur RC même sans notif
            long maxMs = since;
            for (JSONObject o : rc) {
                long t = OpenF1Service.parseIsoPublic(o.optString("date", ""));
                if (t > maxMs) maxMs = t;
            }
            if (maxMs > since) F1LiveStore.setLastRaceControlMs(app, maxMs);

            // Positions / gros coups
            try {
                Map<Integer, Integer> prev = F1LiveStore.getPositions(app);
                Map<Integer, Integer> now = F1LiveDetector.latestPositions(
                        OpenF1Service.fetchPositions(sk));
                if (!now.isEmpty()) {
                    events.addAll(F1LiveDetector.fromPositionJumps(
                            prev, now, drivers, favorites, System.currentTimeMillis()));
                    F1LiveStore.setPositions(app, now);
                }
            } catch (Exception e) {
                Log.w(TAG, "positions", e);
            }

            events.sort((a, b) -> Integer.compare(b.priority, a.priority));
            for (F1LiveEvent ev : events) {
                if (F1LiveStore.wasNotified(app, ev.id)) continue;
                F1LiveStore.setPending(app, ev);
                return ev;
            }
            return null;
        } catch (Exception e) {
            Log.w(TAG, "pollForOffer", e);
            return null;
        }
    }

    /** Snapshot texte pour outil f1(live). */
    public static String statusBrief(Context ctx) throws Exception {
        JSONObject session = OpenF1Service.findLiveRaceSession();
        if (session == null) {
            JSONObject latest = OpenF1Service.fetchLatestSession();
            if (latest != null) {
                String name = latest.optString("session_name", "");
                String loc = latest.optString("location", "");
                return "Pas de course live. Dernière session OpenF1 : "
                        + name + (loc.isEmpty() ? "" : " · " + loc) + ".";
            }
            return "Pas de course live pour le moment.";
        }
        int sk = session.optInt("session_key", 0);
        Map<Integer, OpenF1Service.DriverInfo> drivers =
                OpenF1Service.loadDriversPublic(sk);
        Map<Integer, Integer> pos = F1LiveDetector.latestPositions(
                OpenF1Service.fetchPositions(sk));
        List<String> recent = new ArrayList<>();
        List<JSONObject> rc = OpenF1Service.fetchRaceControl(sk);
        for (int i = Math.max(0, rc.size() - 5); i < rc.size(); i++) {
            String msg = rc.get(i).optString("message", "").trim();
            if (!msg.isEmpty()) recent.add(0, msg.length() > 60 ? msg.substring(0, 57) + "…" : msg);
        }
        return OpenF1Service.liveBrief(session, pos, drivers, recent);
    }

    public static String discussPhrase(Context ctx) {
        String body = F1LiveStore.getPendingBody(ctx);
        if (body == null || body.isEmpty()) {
            return "Qu’est-ce qui se passe en live sur le GP ?";
        }
        return body + " Tu suis ça ?";
    }
}
