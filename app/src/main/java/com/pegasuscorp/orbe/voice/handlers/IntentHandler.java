package com.pegasuscorp.orbe.voice.handlers;

import android.content.Context;
import com.pegasuscorp.orbe.voice.VoiceIntentRouter.RoutedIntent;

public interface IntentHandler {
    /** @return RoutedIntent if matched, else null */
    RoutedIntent tryHandle(Context context, String text, String fold);
}
