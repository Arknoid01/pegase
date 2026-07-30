package com.pegasuscorp.orbe.intentions.rules;

import android.content.Context;

import com.pegasuscorp.orbe.f1companion.F1LiveEvent;
import com.pegasuscorp.orbe.f1companion.F1LiveStore;
import com.pegasuscorp.orbe.intentions.IntentionCandidate;
import com.pegasuscorp.orbe.intentions.IntentionIds;

/** Notif live GP (Safety Car, abandon, pénalité…). */
public final class F1LiveRule {

    private F1LiveRule() {}

    public static IntentionCandidate candidateFor(Context ctx, F1LiveEvent event) {
        if (event == null) return null;
        String title = event.title;
        String body = event.body;
        if (body == null || body.isEmpty()) {
            body = F1LiveStore.getPendingBody(ctx);
        }
        if (body == null || body.isEmpty()) return null;
        if (title == null || title.isEmpty()) title = "Pégase · Live F1";
        return new IntentionCandidate(
                IntentionIds.F1_LIVE,
                title,
                body,
                "f1_live");
    }
}
