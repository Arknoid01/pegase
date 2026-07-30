package com.pegasuscorp.orbe.intentions.rules;

import android.content.Context;
import android.text.TextUtils;

import com.pegasuscorp.orbe.intentions.ContextSnapshot;
import com.pegasuscorp.orbe.intentions.IntentionCandidate;
import com.pegasuscorp.orbe.intentions.IntentionIds;
import com.pegasuscorp.orbe.objects.ProjectObjectStore;

import org.json.JSONObject;

/**
 * Fiche Orion : échec récent + offline → proposer de relancer.
 */
public final class ProjectObjectRule implements IntentionRule {

    private static final long MAX_FAILURE_AGE_MS = 7L * 24L * 60L * 60L * 1000L;

    private final Context app;

    public ProjectObjectRule(Context ctx) {
        this.app = ctx != null ? ctx.getApplicationContext() : null;
    }

    @Override
    public IntentionCandidate evaluate(ContextSnapshot context) {
        if (app == null || context == null || context.briefReadyEvent) return null;
        try {
            ProjectObjectStore store = ProjectObjectStore.getInstance(app);
            if (!store.orionHasRecentFailure(MAX_FAILURE_AGE_MS)) return null;
            JSONObject o = store.getOrion();
            String status = o.optString("status", "");
            if ("READY".equals(status) || "BUSY".equals(status) || "STARTING".equals(status)) {
                return null;
            }
            String err = o.optString("lastError", "").trim();
            if (TextUtils.isEmpty(err)) return null;
            String shortErr = err.length() > 80 ? err.substring(0, 77) + "…" : err;
            return new IntentionCandidate(
                    IntentionIds.ORION_RETRY,
                    "Pégase",
                    "Orion a échoué (" + shortErr + "). Tu veux relancer ?",
                    "orion");
        } catch (Exception e) {
            return null;
        }
    }
}
