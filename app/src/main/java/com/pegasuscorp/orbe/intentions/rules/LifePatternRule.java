package com.pegasuscorp.orbe.intentions.rules;

import android.content.Context;

import com.pegasuscorp.orbe.intentions.ContextSnapshot;
import com.pegasuscorp.orbe.intentions.IntentionCandidate;
import com.pegasuscorp.orbe.life.LifePatternStore;

import java.util.Calendar;
import java.util.List;

/**
 * Plage de vie déclarée active → suggestion rare (ex. ménage / écouteurs).
 */
public final class LifePatternRule implements IntentionRule {

    private final Context app;

    public LifePatternRule(Context ctx) {
        this.app = ctx != null ? ctx.getApplicationContext() : null;
    }

    @Override
    public IntentionCandidate evaluate(ContextSnapshot context) {
        if (app == null || context == null || context.briefReadyEvent) return null;
        try {
            List<LifePatternStore.LifePattern> active =
                    LifePatternStore.getInstance(app).listActiveNow();
            Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(context.nowMs);
            for (LifePatternStore.LifePattern p : active) {
                if (!p.suggestEnabled) continue;
                if (!p.isActiveNow(cal)) continue;
                String body = "Tu es dans ta plage « "
                        + (p.label.isEmpty() ? "rythme" : p.label)
                        + " » (" + p.timeLabel() + "). Quelque chose à lancer ?";
                return new IntentionCandidate(
                        p.intentionId(),
                        "Pégase",
                        body,
                        "life");
            }
        } catch (Exception ignored) {}
        return null;
    }
}
