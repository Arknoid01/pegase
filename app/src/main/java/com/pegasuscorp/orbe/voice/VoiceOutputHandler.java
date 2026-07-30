package com.pegasuscorp.orbe.voice;

import android.content.Context;

import com.pegasuscorp.orbe.conversation.InteractionMood;
import com.pegasuscorp.orbe.conversation.ResponseDelivery;

/**
 * Orchestration voix-OUT : wrap {@link VoiceManager} + {@link ResponseDelivery}.
 * Ne duplique pas SpeechRecognizer / TTS — délègue à VoiceManager.
 */
public final class VoiceOutputHandler {

    private final VoiceManager voiceManager;
    private final ResponseDelivery responseDelivery;

    public VoiceOutputHandler(Context context, VoiceManager voiceManager,
                              ResponseDelivery responseDelivery) {
        this.voiceManager = voiceManager;
        this.responseDelivery = responseDelivery != null
                ? responseDelivery : new ResponseDelivery();
    }

    public VoiceManager getVoiceManager() {
        return voiceManager;
    }

    public ResponseDelivery getResponseDelivery() {
        return responseDelivery;
    }

    public void speak(String text) {
        if (voiceManager != null) voiceManager.speak(text);
    }

    public void speak(String text, Runnable afterSpeak) {
        if (voiceManager != null) voiceManager.speak(text, afterSpeak);
    }

    public void speak(String text, Runnable afterSpeak, long delayMs) {
        if (voiceManager != null) voiceManager.speak(text, afterSpeak, delayMs);
    }

    public void speakWithMood(String text, InteractionMood mood, Runnable onComplete) {
        if (voiceManager == null) {
            if (onComplete != null) onComplete.run();
            return;
        }
        responseDelivery.speak(voiceManager, text, mood, onComplete);
    }

    public void stopSpeaking() {
        if (voiceManager != null) voiceManager.stopSpeaking();
    }

    public boolean isSpeaking() {
        return voiceManager != null && voiceManager.isSpeaking();
    }

    public void beginSpeakStream(Runnable onComplete) {
        if (voiceManager != null) voiceManager.beginSpeakStream(onComplete);
    }

    public void feedSpeakStream(String accumulated) {
        if (voiceManager != null) voiceManager.feedSpeakStream(accumulated);
    }

    public void endSpeakStream(String fullText, Runnable onComplete) {
        if (voiceManager != null) voiceManager.endSpeakStream(fullText, onComplete);
    }

    public void reloadPiperModel() {
        if (voiceManager != null) voiceManager.reloadPiperModel();
    }

    public void applyAndroidTtsSettings() {
        if (voiceManager != null) voiceManager.applyAndroidTtsSettings();
    }

    public void probePiperAsync() {
        if (voiceManager != null) voiceManager.probePiperAsync();
    }

    /** Gate stream TTS : assez de texte ou fin de phrase. */
    public static boolean readyForStreamTts(String accumulated) {
        if (accumulated == null) return false;
        String t = accumulated.trim();
        if (t.length() >= 36) return true;
        return t.endsWith(".") || t.endsWith("!") || t.endsWith("?")
                || t.endsWith("…") || t.endsWith(":");
    }
}
