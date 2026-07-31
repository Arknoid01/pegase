package com.pegasuscorp.orbe.voice;

import android.app.Activity;
import android.content.Context;
import android.view.MotionEvent;
import android.view.View;

/**
 * Branche un bouton micro push-to-talk (appui maintenu).
 */
public final class PttTouchHelper {

    private PttTouchHelper() {}

    public static void attach(View micButton, Context context, Activity host,
            VoicePushToTalk.Channel channel, VoicePushToTalk.Callback callback) {
        if (micButton == null || context == null || channel == null || callback == null) return;
        micButton.setOnTouchListener((v, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                v.setPressed(true);
                VoicePushToTalk.get().begin(context, host, channel, callback);
                return true;
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                v.setPressed(false);
                VoicePushToTalk.get().end(context);
                return true;
            }
            return false;
        });
    }
}
