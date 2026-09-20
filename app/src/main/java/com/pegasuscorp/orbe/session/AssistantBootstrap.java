package com.pegasuscorp.orbe.session;

import android.content.Context;
import android.content.Intent;

import com.pegasuscorp.orbe.chat.ChatSessionRegistry;
import com.pegasuscorp.orbe.voice.PegaseWakeController;

/**
 * Détection cold-start nécessitant l'assistant avant lazy wire (PR3).
 */
public final class AssistantBootstrap {

    private AssistantBootstrap() {}

    public static boolean needsWireOnColdStart(Context context, Intent intent) {
        if (intent != null && intent.getBooleanExtra("wake_activate", false)) {
            return true;
        }
        if (intent != null && intent.getBooleanExtra("resume_chat", false)) {
            return true;
        }
        if (ChatSessionRegistry.isActive()) {
            return true;
        }
        if (PegaseWakeController.isVoiceChatActive()) {
            return true;
        }
        return PegaseWakeController.isInPlaceVoiceActive();
    }

    public static boolean deferAssistantWire(Context context) {
        return HomeLoadPolicy.isLazyBootstrapEnabled(context);
    }
}
