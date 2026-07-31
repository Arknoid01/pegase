package com.pegasuscorp.orbe.learning;

import android.content.Context;
import android.text.TextUtils;

import com.pegasuscorp.orbe.intentions.IntentionEvaluator;
import com.pegasuscorp.orbe.intentions.IntentionIds;
import com.pegasuscorp.orbe.intentions.IntentionPrefs;
import com.pegasuscorp.orbe.intentions.PegaseModeStore;
import com.pegasuscorp.orbe.intentions.location.LocationSituationReader;
import com.pegasuscorp.orbe.intentions.rules.LifePatternSoonRule;
import com.pegasuscorp.orbe.intentions.rules.WorkWifiRule;
import com.pegasuscorp.orbe.life.LifePatternStore;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Bloc situatif présent pour le prompt — faits locaux vérifiables uniquement.
 */
public final class SituationPromptBuilder {

    private static final int MAX_ACTIVE = 2;
    private static final int MAX_ACCEPTED_SCAN = 20;
    private static final int MAX_PREF_BULLETS = 3;

    private SituationPromptBuilder() {}

    public static String promptBlock(Context ctx) {
        return promptBlock(ctx, System.currentTimeMillis(), null);
    }

    /**
     * @param ssidOverride si non null, utilisé à la place du SSID device (tests).
     */
    public static String promptBlock(Context ctx, long nowMs, String ssidOverride) {
        if (ctx == null) return "";
        Context app = ctx.getApplicationContext();
        try {
            List<String> lines = new ArrayList<>();
            Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(nowMs);

            appendActive(app, cal, lines);
            appendSoon(app, cal, lines);
            appendWorkWifi(app, ssidOverride, lines);
            appendLocationContext(app, lines);
            appendAcceptedPrefs(app, lines);

            if (lines.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            sb.append("Situation maintenant (local, ne récite pas) :\n");
            for (String line : lines) {
                sb.append("- ").append(line).append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static void appendActive(Context app, Calendar cal, List<String> lines) {
        List<LifePatternStore.LifePattern> atTime = new ArrayList<>();
        for (LifePatternStore.LifePattern p : LifePatternStore.getInstance(app).listAll()) {
            if (p.active && p.injectPrompt && p.isActiveNow(cal)) atTime.add(p);
        }
        int n = 0;
        for (LifePatternStore.LifePattern p : atTime) {
            if (n >= MAX_ACTIVE) break;
            String label = p.label.isEmpty() ? "Rythme" : p.label;
            lines.add("Rythme en cours : " + label + " (" + p.timeLabel() + ")");
            n++;
        }
    }

    private static void appendSoon(Context app, Calendar cal, List<String> lines) {
        int nowM = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
        for (LifePatternStore.LifePattern p : LifePatternStore.getInstance(app).listAll()) {
            if (!p.active || !p.injectPrompt) continue;
            if (p.isActiveNow(cal)) continue;
            if (p.daysOfWeek != null && !p.daysOfWeek.isEmpty()
                    && !p.daysOfWeek.contains(cal.get(Calendar.DAY_OF_WEEK))) {
                continue;
            }
            int start = p.startHour * 60 + p.startMinute;
            int delta = LifePatternSoonRule.minutesUntil(nowM, start);
            int window = IntentionPrefs.prefersEarlier(app, p.intentionId())
                    ? LifePatternSoonRule.WINDOW_BOOST_MIN
                    : LifePatternSoonRule.WINDOW_MIN;
            if (delta <= 0 || delta > window) continue;
            String label = p.label.isEmpty() ? "rythme" : p.label;
            lines.add(String.format(Locale.FRANCE,
                    "Bientôt : %s dans %d min (%02d:%02d)",
                    label, delta, p.startHour, p.startMinute));
            break; // une seule ligne bientôt
        }
    }

    private static void appendWorkWifi(Context app, String ssidOverride, List<String> lines) {
        String work = WorkWifiRule.normalize(IntentionPrefs.getWorkWifiSsid(app));
        if (work.isEmpty()) return;
        String ssid = ssidOverride != null
                ? WorkWifiRule.normalize(ssidOverride)
                : WorkWifiRule.normalize(IntentionEvaluator.readCurrentSsid(app));
        if (ssid.isEmpty() || WorkWifiRule.isUnknown(ssid)) return;
        if (work.equalsIgnoreCase(ssid)) {
            lines.add("Lieu : Wi‑Fi travail");
        }
    }

    private static void appendLocationContext(Context app, List<String> lines) {
        String place = LocationSituationReader.getCurrentPlaceLabel(app);
        if (place != null && !place.isEmpty()) {
            lines.add("Lieu : " + place);
        }
        if (PegaseModeStore.isDrive(app) && PegaseModeStore.isAutoDriveActive(app)) {
            lines.add("Conduite détectée (vitesse)");
        }
    }

    private static void appendAcceptedPrefs(Context app, List<String> lines) {
        List<LearningCandidate> accepted = new ArrayList<>();
        for (LearningCandidate c : LearningCandidateStore.getInstance(app).listAll()) {
            if (!LearningCandidate.STATUS_ACCEPTED.equals(c.status)) continue;
            if (LearningCandidate.TYPE_INTENTION_SUPPRESS.equals(c.type)
                    || LearningCandidate.TYPE_INTENTION_BOOST.equals(c.type)) {
                accepted.add(c);
            }
        }
        if (accepted.isEmpty()) return;
        Collections.sort(accepted, Comparator.comparingLong((LearningCandidate c) -> c.updatedAtMs)
                .reversed());
        List<String> prefs = new ArrayList<>();
        int scanned = 0;
        for (LearningCandidate c : accepted) {
            if (scanned >= MAX_ACCEPTED_SCAN) break;
            scanned++;
            if (LearningCandidate.TYPE_INTENTION_SUPPRESS.equals(c.type)) {
                prefs.add("ne plus suggérer « "
                        + IntentionIds.displayName(c.targetId) + " »");
            } else if (LearningCandidate.TYPE_INTENTION_BOOST.equals(c.type)) {
                prefs.add("proposer un peu plus tôt « "
                        + IntentionIds.displayName(c.targetId) + " »");
            }
            if (prefs.size() >= MAX_PREF_BULLETS) break;
        }
        if (prefs.isEmpty()) return;
        lines.add("Préférences apprises : " + TextUtils.join(" ; ", prefs));
    }
}
