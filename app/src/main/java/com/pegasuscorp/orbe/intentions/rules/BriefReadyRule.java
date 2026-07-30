package com.pegasuscorp.orbe.intentions.rules;

import com.pegasuscorp.orbe.intentions.ContextSnapshot;
import com.pegasuscorp.orbe.intentions.IntentionCandidate;
import com.pegasuscorp.orbe.intentions.IntentionIds;

/** Déclenché uniquement via flag briefReadyEvent (prefetch). */
public final class BriefReadyRule implements IntentionRule {

    @Override
    public IntentionCandidate evaluate(ContextSnapshot ctx) {
        if (ctx == null || !ctx.briefReadyEvent) return null;
        return new IntentionCandidate(
                IntentionIds.BRIEF_READY,
                "Pégase",
                "Brief du matin prêt.",
                "brief");
    }
}
