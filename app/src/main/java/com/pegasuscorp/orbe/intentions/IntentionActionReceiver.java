package com.pegasuscorp.orbe.intentions;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import com.pegasuscorp.orbe.MainActivity;
import com.pegasuscorp.orbe.PegaseInterfaceState;
import com.pegasuscorp.orbe.chat.ChatVoiceBridge;
import com.pegasuscorp.orbe.f1companion.F1DebriefOffer;
import com.pegasuscorp.orbe.f1companion.F1LivePipeline;
import com.pegasuscorp.orbe.f1companion.F1LiveStore;
import com.pegasuscorp.orbe.f1companion.F1NewsPipeline;
import com.pegasuscorp.orbe.f1companion.F1NewsStore;
import com.pegasuscorp.orbe.learning.LearningEngine;

/**
 * Oui / Plus tard / Plus jamais / Me le rappeler / Ignorer aujourd'hui.
 */
public final class IntentionActionReceiver extends BroadcastReceiver {

    public static final String ACTION = "com.pegasuscorp.orbe.intentions.ACTION";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        Context app = context.getApplicationContext();
        String id = intent.getStringExtra(IntentionNotifier.EXTRA_INTENTION_ID);
        String action = intent.getStringExtra(IntentionNotifier.EXTRA_ACTION);
        if (!IntentionIds.isValid(id) || !IntentionIds.isValidAction(action)) {
            return;
        }
        IntentionNotifier.cancel(app);
        LearningEngine.recordIntentionFeedback(app, id, action);

        switch (action) {
            case IntentionIds.ACTION_NEVER:
                if (IntentionIds.F1_DEBRIEF_READY.equals(id)) {
                    F1DebriefOffer.acknowledgePending(app);
                    Toast.makeText(app, "Pas de débrief pour ce GP", Toast.LENGTH_SHORT).show();
                } else if (IntentionIds.F1_NEWS.equals(id)) {
                    F1NewsStore.acknowledgePending(app);
                    Toast.makeText(app, "Actu ignorée", Toast.LENGTH_SHORT).show();
                } else if (IntentionIds.F1_LIVE.equals(id)) {
                    F1LiveStore.clearPending(app);
                    F1LiveStore.setEnabled(app, false);
                    Toast.makeText(app, "Alertes live F1 coupées", Toast.LENGTH_SHORT).show();
                } else {
                    IntentionPrefs.suppress(app, id);
                    Toast.makeText(app, "Suggestion désactivée", Toast.LENGTH_SHORT).show();
                }
                break;
            case IntentionIds.ACTION_SNOOZE:
                long snoozeMs = IntentionIds.F1_LIVE.equals(id)
                        ? 20L * 60L * 1000L
                        : IntentionPrefs.SNOOZE_MS;
                IntentionPrefs.snoozeFor(app, id, snoozeMs);
                if (IntentionIds.F1_DEBRIEF_READY.equals(id)
                        || IntentionIds.F1_NEWS.equals(id)) {
                    IntentionPrefs.clearLastFired(app, id);
                }
                if (IntentionIds.F1_NEWS.equals(id)) {
                    F1NewsStore.acknowledgePending(app);
                }
                if (IntentionIds.F1_LIVE.equals(id)) {
                    F1LiveStore.clearPending(app);
                }
                Toast.makeText(app, "Plus tard", Toast.LENGTH_SHORT).show();
                break;
            case IntentionIds.ACTION_IGNORE_TODAY:
                IntentionPrefs.ignoreToday(app, id);
                Toast.makeText(app, "Ignoré pour aujourd'hui", Toast.LENGTH_SHORT).show();
                break;
            case IntentionIds.ACTION_REMIND:
                openWithPhrase(app, "Rappelle-moi de brancher mon téléphone dans 30 minutes.");
                break;
            case IntentionIds.ACTION_ACCEPT:
            default:
                handleAccept(app, id);
                break;
        }
    }

    private static void handleAccept(Context app, String id) {
        if (id != null && id.startsWith("life:")) {
            openWithPhrase(app, "Je suis dans ma plage habituelle — tu proposes quelque chose ?");
            return;
        }
        if (id != null && id.startsWith("calendar:")) {
            openWithPhrase(app, "Prépare-moi pour mon prochain rendez-vous.");
            return;
        }
        if (IntentionIds.DRIVE_BT.equals(id)) {
            DriveActions.applyDriveMode(app);
            return;
        }
        if (IntentionIds.ORION_RETRY.equals(id)) {
            PegaseInterfaceState.openOrBringToFront(app, PegaseInterfaceState.TAB_ORION);
            Toast.makeText(app, "Onglet Orion ouvert", Toast.LENGTH_SHORT).show();
            return;
        }
        if (IntentionIds.WORK_WIFI.equals(id)) {
            PegaseModeStore.setMode(app, PegaseModeStore.Mode.WORK);
            Toast.makeText(app, "Mode concentré activé", Toast.LENGTH_SHORT).show();
            openInterface(app);
            return;
        }
        if (IntentionIds.BRIEF_READY.equals(id)) {
            openWithPhrase(app, "Brief du matin");
            return;
        }
        if (IntentionIds.F1_DEBRIEF_READY.equals(id)) {
            F1DebriefOffer.acknowledgePending(app);
            openWithPhrase(app, "Tu en as pensé quoi du GP ?");
            return;
        }
        if (IntentionIds.F1_NEWS.equals(id)) {
            String phrase = F1NewsPipeline.discussPhrase(app);
            F1NewsStore.acknowledgePending(app);
            openWithPhrase(app, phrase);
            return;
        }
        if (IntentionIds.F1_LIVE.equals(id)) {
            String phrase = F1LivePipeline.discussPhrase(app);
            F1LiveStore.clearPending(app);
            openWithPhrase(app, phrase);
            return;
        }
        if (IntentionIds.BATTERY_LOW.equals(id)) {
            openWithPhrase(app, "Rappelle-moi de brancher mon téléphone dans 30 minutes.");
        }
    }

    private static void openWithPhrase(Context app, String phrase) {
        Intent i = new Intent(app, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(ChatVoiceBridge.EXTRA_PENDING_TRANSCRIPT, phrase);
        PegaseInterfaceState.setPendingChatPhrase(phrase);
        app.startActivity(i);
        PegaseInterfaceState.openOrBringToFront(app, PegaseInterfaceState.TAB_CONVERSATION);
    }

    private static void openInterface(Context app) {
        Intent i = new Intent(app, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        app.startActivity(i);
        PegaseInterfaceState.openOrBringToFront(app, PegaseInterfaceState.TAB_CONVERSATION);
    }
}
