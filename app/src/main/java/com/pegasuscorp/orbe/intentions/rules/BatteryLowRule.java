package com.pegasuscorp.orbe.intentions.rules;

import com.pegasuscorp.orbe.intentions.ContextSnapshot;
import com.pegasuscorp.orbe.intentions.IntentionCandidate;
import com.pegasuscorp.orbe.intentions.IntentionIds;
import com.pegasuscorp.orbe.intentions.IntentionPrefs;

/**
 * Franchissement ≥20 % → &lt;20 %, pas en charge.
 */
public final class BatteryLowRule implements IntentionRule {

    @Override
    public IntentionCandidate evaluate(ContextSnapshot ctx) {
        if (ctx == null) return null;
        if (ctx.charging) return null;
        if (ctx.batteryPercent < 0) return null;
        int prev = ctx.lastSeenBatteryPercent;
        // Premier sample : mémoriser seulement (pas d'edge)
        if (prev < 0) return null;
        boolean crossed = prev >= IntentionPrefs.BATTERY_THRESHOLD
                && ctx.batteryPercent < IntentionPrefs.BATTERY_THRESHOLD;
        if (!crossed) return null;
        return new IntentionCandidate(
                IntentionIds.BATTERY_LOW,
                "Pégase",
                "Ta batterie est à " + ctx.batteryPercent
                        + " %. Pense à brancher ton téléphone.",
                "battery");
    }
}
