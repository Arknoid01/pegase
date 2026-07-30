package com.pegasuscorp.orbe.intentions.rules;

import com.pegasuscorp.orbe.f1companion.WeekendSnapshot;
import com.pegasuscorp.orbe.intentions.IntentionCandidate;
import com.pegasuscorp.orbe.intentions.IntentionIds;

/** Construit le candidat notif pour un nouveau GP (déclenché hors tick capteurs). */
public final class F1DebriefReadyRule {

    private F1DebriefReadyRule() {}

    public static IntentionCandidate candidateFor(WeekendSnapshot snap) {
        if (snap == null || !snap.hasRaceResults()) return null;
        String event = snap.event != null && !snap.event.isEmpty()
                ? snap.event : "Grand Prix";
        String podium = snap.podiumLine();
        String body = "Le débrief du " + event + " est prêt.";
        if (podium != null && !podium.isEmpty()) {
            body += " Podium : " + podium + ".";
        }
        return new IntentionCandidate(
                IntentionIds.F1_DEBRIEF_READY,
                "Pégase · F1",
                body,
                "f1");
    }
}
