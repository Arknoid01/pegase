package com.pegasuscorp.orbe.voice;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Lecture vocale phrase par phrase : Piper ou TTS Android (choix utilisateur).
 * Prétraitement {@link SpeechFormatter} dans les deux cas.
 */
public class SpeechOutput {

    private static final String TAG = "SpeechOutput";

    private final Context appContext;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final PiperTtsEngine piper;
    private TextToSpeech androidTts;
    private boolean androidReady = false;
    /** Voix préférée absente / filtrée → on force Piper si possible. */
    private boolean androidVoiceUnavailable = false;
    private volatile boolean released = false;
    private Runnable pendingDone;
    private final List<String> queue = new ArrayList<>();
    private int queueIndex = 0;
    private boolean speaking = false;
    private int sessionId = 0;
    private boolean streamMode = false;
    private int streamChunksQueued = 0;

    public SpeechOutput(Context context) {
        appContext = context.getApplicationContext();
        piper = new PiperTtsEngine(appContext);
        initAndroidTts();
    }

    private void initAndroidTts() {
        androidReady = false;
        androidVoiceUnavailable = false;
        TextToSpeech engine = new TextToSpeech(appContext, status -> {
            if (released) return;
            TextToSpeech tts = androidTts;
            if (tts == null) return;
            if (status == TextToSpeech.SUCCESS) {
                try {
                    tts.setLanguage(Locale.FRENCH);
                    AndroidTtsStore.updateCachedVoices(
                            AndroidTtsStore.listFrenchVoices(tts, false));
                    logFrenchVoices(tts);
                    logSystemSelectedFrenchVoice(tts);
                    androidVoiceUnavailable = !AndroidTtsStore.applyPreferredVoice(appContext, tts);
                    if (androidVoiceUnavailable) {
                        Log.w(TAG, "Voix système préférée indisponible — Piper en repli si prêt");
                    } else {
                        Log.i(TAG, "Voix système appliquée : "
                                + AndroidTtsStore.getVoiceName(appContext));
                    }
                    androidReady = true;
                    tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                        @Override public void onStart(String id) {}
                        @Override public void onError(String id) { onAndroidDone(); }
                        @Override public void onDone(String id) { onAndroidDone(); }
                    });
                    android.os.Bundle params = new android.os.Bundle();
                    params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 0f);
                    tts.speak(" ", TextToSpeech.QUEUE_ADD, params, "warmup");
                } catch (Exception e) {
                    Log.w(TAG, "init Android TTS", e);
                    androidReady = false;
                }
            } else {
                androidReady = false;
                Log.w(TAG, "TextToSpeech onInit status=" + status);
            }
        });
        androidTts = engine;
    }

    /** Re-applique préférences voix (après changement UI) sans shutdown complet. */
    public void applyAndroidTtsSettings() {
        if (released || androidTts == null || !androidReady) return;
        try {
            androidTts.setLanguage(Locale.FRENCH);
            androidVoiceUnavailable = !AndroidTtsStore.applyPreferredVoice(appContext, androidTts);
            AndroidTtsStore.updateCachedVoices(
                    AndroidTtsStore.listFrenchVoices(androidTts, false));
        } catch (Exception e) {
            androidVoiceUnavailable = true;
            Log.w(TAG, "applyAndroidTtsSettings", e);
        }
    }

    /**
     * Dump des voix FR après {@code onInit(SUCCESS)}.
     * Filtrer Logcat : {@code adb logcat -s SpeechOutput:I}
     */
    private static void logFrenchVoices(TextToSpeech tts) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return;
        Set<Voice> voices;
        try {
            voices = tts.getVoices();
        } catch (Exception e) {
            return;
        }
        if (voices == null || voices.isEmpty()) return;
        Log.i(TAG, "=== Voix françaises (TTS système) ===");
        int i = 0;
        for (Voice voice : voices) {
            if (voice == null) continue;
            Locale loc = voice.getLocale();
            String lang = loc != null ? loc.getLanguage() : "";
            if (lang == null || !lang.toLowerCase(Locale.ROOT).startsWith("fr")) continue;
            i++;
            Log.i(TAG, String.format(Locale.US,
                    "[%d] name=%s | locale=%s | network=%s | quality=%d",
                    i, voice.getName(), loc,
                    voice.isNetworkConnectionRequired(), voice.getQuality()));
        }
        Log.i(TAG, "=== Fin liste (" + i + " voix fr) ===");
    }

    private static void logSystemSelectedFrenchVoice(TextToSpeech tts) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return;
        try {
            Voice current = tts.getVoice();
            Log.i(TAG, ">>> Voix active (après setLanguage FR) : " + formatVoiceBrief(current));
        } catch (Exception ignored) {}
    }

    private static String formatVoiceBrief(Voice voice) {
        if (voice == null) return "(null)";
        return "name=" + voice.getName()
                + " | locale=" + voice.getLocale()
                + " | network=" + voice.isNetworkConnectionRequired()
                + " | quality=" + voice.getQuality();
    }

    public void beginSpeakStream(Runnable onComplete) {
        streamMode = true;
        streamChunksQueued = 0;
        pendingDone = onComplete;
        if (!speaking) {
            queue.clear();
            queueIndex = 0;
        }
    }

    public void feedSpeakStream(String fullText) {
        if (!streamMode || fullText == null) return;
        List<String> chunks = SpeechFormatter.formatChunks(appContext, fullText);
        enqueueNewChunks(chunks, true);
    }

    public void endSpeakStream(String fullText, Runnable onComplete) {
        streamMode = false;
        pendingDone = onComplete;
        if (fullText != null) {
            List<String> chunks = SpeechFormatter.formatChunks(appContext, fullText);
            enqueueNewChunks(chunks, false);
        }
        if (!speaking && !queue.isEmpty()) {
            speaking = true;
            final int session = sessionId;
            main.postDelayed(() -> speakNext(session), 80);
        } else if (!speaking) {
            fireDone();
        }
    }

    private void enqueueNewChunks(List<String> chunks, boolean leaveLastOpen) {
        int limit = chunks.size();
        if (leaveLastOpen && limit > 0) limit--;
        while (streamChunksQueued < limit) {
            queue.add(chunks.get(streamChunksQueued++));
        }
        if (!speaking && !queue.isEmpty()) {
            speaking = true;
            final int session = sessionId;
            main.postDelayed(() -> speakNext(session), 80);
        }
    }

    public void speak(String text, Runnable onComplete) {
        streamMode = false;
        streamChunksQueued = 0;
        final int session = ++sessionId;
        cancelPlayback();
        pendingDone = onComplete;
        queue.clear();
        queue.addAll(SpeechFormatter.formatChunks(appContext, text));
        queueIndex = 0;
        if (queue.isEmpty()) {
            speaking = false;
            fireDone();
            return;
        }
        speaking = true;
        main.postDelayed(() -> speakNext(session), 160);
    }

    public void stop() {
        streamMode = false;
        streamChunksQueued = 0;
        sessionId++;
        cancelPlayback();
        pendingDone = null;
    }

    private void cancelPlayback() {
        speaking = false;
        queue.clear();
        queueIndex = 0;
        piper.stop();
        if (androidTts != null) {
            try { androidTts.stop(); } catch (Exception ignored) {}
        }
    }

    public boolean isSpeaking() {
        return speaking || piper.isSpeaking();
    }

    public void release() {
        released = true;
        androidReady = false;
        androidInitWaitAttempts = 0;
        stop();
        piper.release();
        TextToSpeech tts = androidTts;
        androidTts = null;
        if (tts != null) {
            try {
                tts.shutdown();
            } catch (Exception ignored) {}
        }
    }

    /** Recrée le moteur Android après shutdown (reprise). */
    public void reinitAndroidTtsIfNeeded() {
        if (released) return;
        if (androidTts != null && androidReady) {
            applyAndroidTtsSettings();
            return;
        }
        if (androidTts != null) {
            try { androidTts.shutdown(); } catch (Exception ignored) {}
            androidTts = null;
        }
        initAndroidTts();
    }

    public void reloadPiper() {
        sessionId++;
        cancelPlayback();
        pendingDone = null;
        piper.reload();
    }

    public void probePiperAsync() {
        if (!PiperModelStore.usePiper(appContext) || !PiperModelStore.isModelReady(appContext)) {
            return;
        }
        piper.probeAsync();
    }

    private int androidSession;
    private int androidInitWaitAttempts;

    private void speakNext(int session) {
        if (session != sessionId) return;
        if (!speaking || queueIndex >= queue.size()) {
            speaking = false;
            androidInitWaitAttempts = 0;
            fireDone();
            return;
        }
        String sentence = queue.get(queueIndex++);
        boolean preferPiper = PiperModelStore.usePiper(appContext);
        boolean piperOk = piper.isReady();
        boolean systemOk = androidReady && androidTts != null && !released
                && !androidVoiceUnavailable;

        if (preferPiper && piperOk) {
            androidInitWaitAttempts = 0;
            piper.speak(sentence, () -> onChunkDone(session));
            return;
        }
        if (!preferPiper && systemOk) {
            androidInitWaitAttempts = 0;
            androidTts.speak(sentence, TextToSpeech.QUEUE_FLUSH, null, "orbe-" + queueIndex);
            androidSession = session;
            return;
        }
        // Repli : si système demandé mais voix absente / pas prêt → Piper
        if (!preferPiper && piperOk) {
            androidInitWaitAttempts = 0;
            Log.i(TAG, "Repli Piper (voix système indisponible ou TTS pas prêt)");
            piper.speak(sentence, () -> onChunkDone(session));
            return;
        }
        // Piper demandé mais pas prêt → système si possible
        if (preferPiper && systemOk) {
            androidInitWaitAttempts = 0;
            androidTts.speak(sentence, TextToSpeech.QUEUE_FLUSH, null, "orbe-" + queueIndex);
            androidSession = session;
            return;
        }
        // Attendre onInit Android
        if (!released && androidTts != null && !androidReady && androidInitWaitAttempts < 40) {
            androidInitWaitAttempts++;
            queueIndex--; // rejouer la même phrase
            main.postDelayed(() -> speakNext(session), 100);
            return;
        }
        androidInitWaitAttempts = 0;
        onChunkDone(session);
    }

    private void onAndroidDone() {
        final int session = androidSession;
        main.post(() -> onChunkDone(session));
    }

    private void onChunkDone(int session) {
        main.post(() -> {
            if (session != sessionId) return;
            speakNext(session);
        });
    }

    private void fireDone() {
        if (pendingDone != null) {
            Runnable r = pendingDone;
            pendingDone = null;
            main.post(r);
        }
    }
}
