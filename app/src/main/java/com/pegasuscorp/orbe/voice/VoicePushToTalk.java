package com.pegasuscorp.orbe.voice;

import android.app.Activity;
import android.content.Context;

import com.pegasuscorp.orbe.chat.ChatVoiceBridge;

/**
 * Push-to-talk partagé — Discussion texte et bulle copilote.
 * Réutilise {@link VoiceManager} via {@link ChatVoiceBridge}.
 */
public final class VoicePushToTalk {

    public enum Channel {
        DISCUSSION,
        COPILOT
    }

    public interface Callback {
        void onTranscript(String text);

        void onListeningChanged(boolean listening);

        /** Relâchement sans phrase reconnue. */
        default void onEmpty() {}
    }

    private static VoicePushToTalk instance;

    private Channel channel;
    private Callback callback;
    private boolean active;

    public static synchronized VoicePushToTalk get() {
        if (instance == null) instance = new VoicePushToTalk();
        return instance;
    }

    public boolean isActive() {
        return active;
    }

    public Channel getChannel() {
        return channel;
    }

    /** Appui maintenu — démarre l'écoute STT. */
    public synchronized void begin(Context context, Activity host, Channel ch, Callback cb) {
        if (context == null || ch == null || cb == null) return;
        if (VoiceMuteStore.isMuted(context)) return;
        active = true;
        channel = ch;
        callback = cb;
        PegaseWakeController.setPushToTalkActive(true);
        WakeStateSoundCue.playListeningOn(context);

        VoiceManager voice = ChatVoiceBridge.getSharedVoice(context);
        if (host != null) {
            voice.attachHost(host);
        }
        voice.setOnListeningStateListener(listening -> {
            Callback c = callback;
            if (c != null) c.onListeningChanged(listening);
            PegaseWakeController.setMicListening(listening);
        });
        voice.setOnListenFailed(() -> {
            Callback c = callback;
            if (c != null) c.onEmpty();
        });
        voice.startPushToTalkListening();
        PegaseWakeController.setMicListening(true);
    }

    /** Relâchement — finalise l'écoute et route la transcription. */
    public synchronized void end(Context context) {
        if (!active) return;
        VoiceManager voice = context != null ? ChatVoiceBridge.getSharedVoice(context) : null;
        if (voice != null) {
            voice.finishPushToTalkListening();
        }
        PegaseWakeController.setMicListening(false);
        PegaseWakeController.setPushToTalkActive(false);
        WakeStateSoundCue.playListeningOff(context);
        active = false;
        channel = null;
        callback = null;
        if (voice != null) {
            voice.setOnListeningStateListener(null);
            voice.setOnListenFailed(null);
        }
    }

    /** Appelé par {@link ChatVoiceBridge#deliverTranscript} quand PTT actif. */
    public synchronized boolean deliverTranscript(String transcript) {
        if (!active || callback == null) return false;
        if (transcript == null || transcript.trim().isEmpty()) {
            callback.onEmpty();
            return true;
        }
        callback.onTranscript(transcript.trim());
        return true;
    }

    public synchronized void cancel(Context context) {
        if (!active) return;
        VoiceManager voice = context != null ? ChatVoiceBridge.getSharedVoice(context) : null;
        if (voice != null) {
            voice.cancelPushToTalkListening();
        }
        PegaseWakeController.setMicListening(false);
        PegaseWakeController.setPushToTalkActive(false);
        WakeStateSoundCue.playListeningOff(context);
        active = false;
        channel = null;
        callback = null;
        if (voice != null) {
            voice.setOnListeningStateListener(null);
            voice.setOnListenFailed(null);
        }
    }
}
