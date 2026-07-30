package com.pegasuscorp.orbe.prefetch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Reprogramme l'alarme matin après redémarrage + rattrapage prefetch si besoin.
 */
public final class PrefetchBootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        String action = intent.getAction();
        if (action == null) return;
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            return;
        }
        Context app = context.getApplicationContext();
        try {
            PrefetchScheduler.ensureScheduled(app);
        } catch (Exception ignored) {}
        try {
            com.pegasuscorp.orbe.intentions.IntentionScheduler.ensureScheduled(app);
        } catch (Exception ignored) {}
        try {
            com.pegasuscorp.orbe.f1companion.F1NewsScheduler.ensureScheduled(app);
        } catch (Exception ignored) {}
        try {
            com.pegasuscorp.orbe.f1companion.F1LiveScheduler.ensureScheduled(app);
        } catch (Exception ignored) {}
        try {
            // Rattrapage si le téléphone a redémarré après l'heure prévue
            PrefetchService.run(app);
        } catch (Exception ignored) {}
    }
}
