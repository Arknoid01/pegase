package com.pegasuscorp.orbe.intentions.rules;

import android.content.Context;

import com.pegasuscorp.orbe.intentions.CalendarSoon;
import com.pegasuscorp.orbe.intentions.ContextSnapshot;
import com.pegasuscorp.orbe.intentions.IntentionCandidate;

/**
 * RDV dans 10–45 min → suggestion rare.
 */
public final class CalendarSoonRule implements IntentionRule {

    private final Context app;

    public CalendarSoonRule(Context ctx) {
        this.app = ctx != null ? ctx.getApplicationContext() : null;
    }

    @Override
    public IntentionCandidate evaluate(ContextSnapshot context) {
        if (app == null || context == null || context.briefReadyEvent) return null;
        CalendarSoon.Event ev = CalendarSoon.nextSoon(app, context.nowMs);
        if (ev == null) return null;
        String body = "Tu as « " + ev.title + " » à " + CalendarSoon.formatTime(ev.beginMs)
                + " (dans " + ev.minutesLabel(context.nowMs) + " min).";
        return new IntentionCandidate(
                ev.intentionId(),
                "Pégase",
                body,
                "calendar");
    }
}
