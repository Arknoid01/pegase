package com.pegasuscorp.orbe.learning;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;

/**
 * Oui / Plus tard / Non sur une hypothèse d'apprentissage.
 */
public final class LearningActionReceiver extends BroadcastReceiver {

    public static final String ACTION = "com.pegasuscorp.orbe.learning.ACTION";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        Context app = context.getApplicationContext();
        String id = intent.getStringExtra(LearningNotifier.EXTRA_CANDIDATE_ID);
        String action = intent.getStringExtra(LearningNotifier.EXTRA_ACTION);
        if (TextUtils.isEmpty(id) || TextUtils.isEmpty(action)) return;
        LearningNotifier.cancel(app);
        switch (action) {
            case LearningNotifier.ACTION_ACCEPT:
                LearningFeedback.accept(app, id);
                break;
            case LearningNotifier.ACTION_SNOOZE:
                LearningFeedback.snooze(app, id);
                break;
            case LearningNotifier.ACTION_REFUSE:
            default:
                LearningFeedback.refuse(app, id);
                break;
        }
    }
}
