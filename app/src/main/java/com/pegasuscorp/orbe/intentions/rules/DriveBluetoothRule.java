package com.pegasuscorp.orbe.intentions.rules;

import com.pegasuscorp.orbe.intentions.ContextSnapshot;
import com.pegasuscorp.orbe.intentions.IntentionCandidate;
import com.pegasuscorp.orbe.intentions.IntentionIds;

/**
 * Entrée Bluetooth voiture : pas connecté → connecté.
 */
public final class DriveBluetoothRule implements IntentionRule {

    @Override
    public IntentionCandidate evaluate(ContextSnapshot ctx) {
        if (ctx == null || ctx.briefReadyEvent) return null;
        if (!ctx.carBtConnected) return null;
        if (ctx.lastSeenCarBtConnected) return null; // déjà connecté
        return new IntentionCandidate(
                IntentionIds.DRIVE_BT,
                "Pégase",
                "Voiture détectée. Passer en mode conduite ?",
                "drive");
    }
}
