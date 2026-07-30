package com.pegasuscorp.orbe.intentions.rules;

import com.pegasuscorp.orbe.f1companion.F1NewsFilter;
import com.pegasuscorp.orbe.f1companion.F1NewsStore;
import com.pegasuscorp.orbe.intentions.IntentionCandidate;
import com.pegasuscorp.orbe.intentions.IntentionIds;

import android.content.Context;

/** Notif actu F1 filtrée (équipes favorites). */
public final class F1NewsRule {

    private F1NewsRule() {}

    public static IntentionCandidate candidateFor(Context ctx, F1NewsFilter.Match match) {
        if (ctx == null) return null;
        String body = F1NewsStore.getPendingSummary(ctx);
        if (body == null || body.isEmpty()) {
            if (match != null && match.item != null) body = match.item.title;
        }
        if (body == null || body.isEmpty()) return null;
        String team = F1NewsStore.getPendingTeam(ctx);
        if (team == null || team.isEmpty()) {
            team = match != null ? match.primaryTeamLabel() : "F1";
        }
        return new IntentionCandidate(
                IntentionIds.F1_NEWS,
                "Pégase · " + team,
                body,
                "f1_news");
    }
}
