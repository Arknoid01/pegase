package com.pegasuscorp.orbe.intentions.rules;

import android.content.Context;

import com.pegasuscorp.orbe.intentions.ContextSnapshot;
import com.pegasuscorp.orbe.intentions.IntentionCandidate;
import com.pegasuscorp.orbe.intentions.IntentionPrefs;
import com.pegasuscorp.orbe.life.LifePatternStore;

import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * Bientôt le début d'un rythme déclaré → suggestion rare (avant isActiveNow).
 */
public final class LifePatternSoonRule implements IntentionRule {

    public static final int WINDOW_MIN = 15;
    public static final int WINDOW_BOOST_MIN = 25;

    private final Context app;

    public LifePatternSoonRule(Context ctx) {
        this.app = ctx != null ? ctx.getApplicationContext() : null;
    }

    @Override
    public IntentionCandidate evaluate(ContextSnapshot context) {
        if (app == null || context == null || context.briefReadyEvent) return null;
        try {
            Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(context.nowMs);
            int nowM = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
            List<LifePatternStore.LifePattern> all = LifePatternStore.getInstance(app).listAll();
            for (LifePatternStore.LifePattern p : all) {
                if (!p.active || !p.suggestEnabled) continue;
                if (p.isActiveNow(cal)) continue;
                if (!daysOk(p, cal)) continue;
                int start = p.startHour * 60 + p.startMinute;
                int delta = minutesUntil(nowM, start);
                int window = IntentionPrefs.prefersEarlier(app, p.intentionId())
                        ? WINDOW_BOOST_MIN : WINDOW_MIN;
                if (delta <= 0 || delta > window) continue;
                String label = p.label.isEmpty() ? "rythme" : p.label;
                String body = String.format(Locale.FRANCE,
                        "Dans %d min : %s (%02d:%02d). Tu prépares quelque chose ?",
                        delta, label, p.startHour, p.startMinute);
                return new IntentionCandidate(
                        p.intentionId(),
                        "Pégase",
                        body,
                        "life");
            }
        } catch (Exception ignored) {}
        return null;
    }

    public static int minutesUntil(int nowM, int startM) {
        int d = startM - nowM;
        if (d < 0) d += 24 * 60;
        return d;
    }

    private static boolean daysOk(LifePatternStore.LifePattern p, Calendar cal) {
        if (p.daysOfWeek == null || p.daysOfWeek.isEmpty()) return true;
        return p.daysOfWeek.contains(cal.get(Calendar.DAY_OF_WEEK));
    }
}
