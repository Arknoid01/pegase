package com.pegasuscorp.orbe.voice;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.util.ArrayList;

/**
 * Entrée vocale (SpeechRecognizer) + sortie via SpeechOutput (Piper ou TTS Android).
 *
 * Flux simple : speak(text, callback) → Piper/TTS → callback → startListening().
 * Aucune relance automatique — évite toute boucle.
 */
public class VoiceManager {

    public interface OnResult {
        void onTranscript(String transcript);
    }

    public interface OnListeningStateListener {
        void onListeningChanged(boolean listening);
    }

    private final Context appContext;
    private final OnResult onResult;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SpeechOutput speechOutput;
    private WeakReference<Activity> hostActivity;
    private SpeechRecognizer recognizer;
    private boolean listening = false;
    private Runnable pendingListenRunnable;
    private Runnable pendingSpeakDelayRunnable;

    private Runnable onListenFailed;
    private OnListeningStateListener listeningStateListener;
    private volatile boolean pushToTalkMode;
    /** SCO Bluetooth partagé avec le wake word — requis pour que le micro casque marche en STT. */
    private final KwsAudioRouteManager audioRoute;
    private boolean scoHeldForStt;

    public VoiceManager(Context ctx, OnResult onResult) {
        this.appContext = ctx.getApplicationContext();
        this.onResult = onResult;
        this.speechOutput = new SpeechOutput(appContext);
        this.audioRoute = KwsAudioRouteManager.getInstance(appContext);
    }

    /** SpeechRecognizer exige un contexte Activity sur plusieurs versions d'Android. */
    public void attachHost(Activity activity) {
        if (activity == null) return;
        Activity prev = hostActivity != null ? hostActivity.get() : null;
        hostActivity = new WeakReference<>(activity);
        // Même Activity + recognizer vivant → évite le hitch au retour HOME
        if (prev == activity && recognizer != null) {
            return;
        }
        rebuildRecognizer();
    }

    public void detachHost(Activity activity) {
        if (hostActivity == null) return;
        Activity host = hostActivity.get();
        if (host != null && host != activity) return;
        stopListening();
        destroyRecognizer();
        hostActivity = null;
    }

    /** PTT : une écoute pendant l'appui (silences courts). */
    public void startPushToTalkListening() {
        pushToTalkMode = true;
        if (recognizer == null) rebuildRecognizer();
        if (recognizer == null) {
            Toast.makeText(appContext, "Reconnaissance vocale indisponible",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (listening) {
            try {
                recognizer.cancel();
            } catch (RuntimeException ignored) {}
            setListeningActive(false);
        }
        beginListeningWithBluetoothRoute(true);
    }

    /** Relâchement PTT — demande les résultats finaux. */
    public void finishPushToTalkListening() {
        pushToTalkMode = false;
        if (recognizer != null && listening) {
            try {
                recognizer.stopListening();
            } catch (RuntimeException ex) {
                try {
                    recognizer.cancel();
                } catch (RuntimeException ignored) {}
                setListeningActive(false);
                notifyListenFailed();
            }
        }
    }

    public void cancelPushToTalkListening() {
        pushToTalkMode = false;
        stopListening();
    }

    private Intent buildListenIntent(boolean ptt) {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, "Parlez à Pégase");
        if (ptt) {
            i.putExtra("android.speech.extra.SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLISECONDS", 2500)
                    .putExtra("android.speech.extra.SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLISECONDS", 2000)
                    .putExtra("android.speech.extra.SPEECH_INPUT_MINIMUM_LENGTH_MILLISECONDS", 300);
        } else {
            i.putExtra("android.speech.extra.SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLISECONDS", 1500)
                    .putExtra("android.speech.extra.SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLISECONDS", 1200)
                    .putExtra("android.speech.extra.SPEECH_INPUT_MINIMUM_LENGTH_MILLISECONDS", 800);
        }
        return i;
    }

    /** Démarre l'écoute une seule fois. */
    public void startListening() {
        pushToTalkMode = false;
        if (recognizer == null) {
            rebuildRecognizer();
        }
        if (recognizer == null) {
            Toast.makeText(appContext, "Reconnaissance vocale indisponible",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (listening) return;
        beginListeningWithBluetoothRoute(false);
    }

    /**
     * Active le SCO Bluetooth si un casque est connecté, puis lance SpeechRecognizer.
     * Sans ça le STT reste sur le micro téléphone alors que le wake word utilisait le casque.
     */
    private void beginListeningWithBluetoothRoute(boolean ptt) {
        final boolean wantBt = audioRoute.wantsBluetoothMic();
        audioRoute.prepareCaptureAsync(ok -> {
            if (recognizer == null) {
                rebuildRecognizer();
            }
            if (recognizer == null) {
                if (wantBt && ok) releaseSttScoAfterListen();
                Toast.makeText(appContext, "Reconnaissance vocale indisponible",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            if (listening) {
                if (wantBt && ok) {
                    // prepare a acquis un hold inutilisé
                    scoHeldForStt = true;
                    releaseSttScoAfterListen();
                }
                return;
            }
            scoHeldForStt = wantBt && ok;
            Intent i = buildListenIntent(ptt);
            try {
                android.util.Log.i("VoiceManager", "startListening route="
                        + audioRoute.describeRoute()
                        + " scoHeld=" + scoHeldForStt);
                recognizer.startListening(i);
                setListeningActive(true);
            } catch (RuntimeException ex) {
                setListeningActive(false);
                releaseSttScoAfterListen();
                destroyRecognizer();
                rebuildRecognizer();
                Toast.makeText(appContext, "Micro indisponible", Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** Appelé après que Pégase a fini de répondre — ré-ouvre le micro. */
    public void resumeListeningAfterReply() {
        resumeListeningAfterReply(400);
    }

    public void resumeListeningAfterReply(long delayMs) {
        cancelScheduledListening();
        pendingListenRunnable = () -> {
            pendingListenRunnable = null;
            startListening();
        };
        main.postDelayed(pendingListenRunnable, Math.max(0, delayMs));
    }

    /** Annule une reprise d'écoute programmée (évite le micro qui se rouvre en pleine réponse). */
    public void cancelScheduledListening() {
        if (pendingListenRunnable != null) {
            main.removeCallbacks(pendingListenRunnable);
            pendingListenRunnable = null;
        }
    }

    private void cancelPendingSpeakDelay() {
        if (pendingSpeakDelayRunnable != null) {
            main.removeCallbacks(pendingSpeakDelayRunnable);
            pendingSpeakDelayRunnable = null;
        }
    }

    public void stopListening() {
        cancelScheduledListening();
        if (recognizer != null && listening) {
            try {
                recognizer.cancel();
            } catch (RuntimeException ignored) {}
            setListeningActive(false);
        }
        // Libérer SCO pour que TTS / musique repassent en A2DP.
        releaseSttScoAfterListen();
    }

    /** Libère le hold STT même si listening était déjà false (après résultat). */
    private void releaseSttScoAfterListen() {
        if (scoHeldForStt) {
            scoHeldForStt = false;
            audioRoute.releaseCaptureAsync();
        }
    }

    public void speak(String text, Runnable afterSpeak) {
        speak(text, afterSpeak, 0);
    }

    public void speak(String text, Runnable afterSpeak, long delayMs) {
        cancelScheduledListening();
        cancelPendingSpeakDelay();
        if (recognizer != null && listening) {
            try {
                recognizer.cancel();
            } catch (RuntimeException ignored) {}
            setListeningActive(false);
        }
        // Couper SCO avant TTS → sortie casque en A2DP (meilleure qualité).
        releaseSttScoAfterListen();
        if (delayMs <= 0) {
            speechOutput.speak(text, afterSpeak);
        } else {
            pendingSpeakDelayRunnable = () -> {
                pendingSpeakDelayRunnable = null;
                speechOutput.speak(text, afterSpeak);
            };
            main.postDelayed(pendingSpeakDelayRunnable, delayMs);
        }
    }

    public void beginSpeakStream(Runnable onComplete) {
        cancelScheduledListening();
        cancelPendingSpeakDelay();
        if (recognizer != null && listening) {
            try {
                recognizer.cancel();
            } catch (RuntimeException ignored) {}
            setListeningActive(false);
        }
        releaseSttScoAfterListen();
        speechOutput.beginSpeakStream(onComplete);
    }

    public void feedSpeakStream(String accumulated) {
        speechOutput.feedSpeakStream(accumulated);
    }

    public void endSpeakStream(String fullText, Runnable onComplete) {
        speechOutput.endSpeakStream(fullText, onComplete);
    }

    public void stopSpeaking() {
        cancelScheduledListening();
        cancelPendingSpeakDelay();
        speechOutput.stop();
    }

    public void speak(String text) {
        speak(text, null);
    }

    public boolean isSpeaking() {
        return speechOutput.isSpeaking();
    }

    public void release() {
        cancelScheduledListening();
        cancelPendingSpeakDelay();
        destroyRecognizer();
        releaseSttScoAfterListen();
        hostActivity = null;
        speechOutput.release();
    }

    public void reloadPiperModel() {
        speechOutput.reloadPiper();
    }

    /** Applique le choix de voix TTS système (Personnalisation). */
    public void applyAndroidTtsSettings() {
        speechOutput.applyAndroidTtsSettings();
    }

    public void reinitAndroidTtsIfNeeded() {
        speechOutput.reinitAndroidTtsIfNeeded();
    }

    /** Teste Piper au démarrage pour basculer sur TTS Android si la couche native échoue. */
    public void probePiperAsync() {
        speechOutput.probePiperAsync();
    }

    public void setOnListenFailed(Runnable callback) {
        onListenFailed = callback;
    }

    public void setOnListeningStateListener(OnListeningStateListener listener) {
        listeningStateListener = listener;
    }

    private void rebuildRecognizer() {
        destroyRecognizer();
        Context recContext = recognitionContext();
        if (recContext == null || !SpeechRecognizer.isRecognitionAvailable(recContext)) {
            return;
        }
        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(recContext);
            recognizer.setRecognitionListener(new SimpleListener());
        } catch (RuntimeException ex) {
            recognizer = null;
        }
    }

    private void destroyRecognizer() {
        if (recognizer != null) {
            try {
                recognizer.destroy();
            } catch (RuntimeException ignored) {}
            recognizer = null;
        }
        listening = false;
    }

    public Context getAppContext() {
        return appContext;
    }

    private Context recognitionContext() {
        if (hostActivity != null) {
            Activity host = hostActivity.get();
            if (host != null && !host.isFinishing()) {
                return host;
            }
        }
        return appContext;
    }

    private void setListeningActive(boolean active) {
        if (listening == active) return;
        listening = active;
        // Capturer le listener : PTT peut le nullifier avant l'exécution du post
        // (crash au retour HOME après ouverture d'un lien web / Chrome).
        final OnListeningStateListener listener = listeningStateListener;
        final boolean now = listening;
        if (listener != null) {
            main.post(() -> {
                OnListeningStateListener l = listeningStateListener;
                if (l != null) {
                    l.onListeningChanged(now);
                }
            });
        }
    }

    private void notifyListenFailed() {
        if (onListenFailed != null) main.post(onListenFailed);
    }

    private class SimpleListener implements RecognitionListener {
        @Override
        public void onResults(Bundle results) {
            setListeningActive(false);
            pushToTalkMode = false;
            releaseSttScoAfterListen();
            ArrayList<String> list =
                    results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (list != null && !list.isEmpty()) {
                String transcript = pickBestTranscript(list);
                if (!transcript.isEmpty()) {
                    onResult.onTranscript(transcript);
                    return;
                }
            }
            notifyListenFailed();
        }

        private String pickBestTranscript(ArrayList<String> hypotheses) {
            String best = null;
            double bestScore = -1;
            for (String raw : hypotheses) {
                if (raw == null) continue;
                String trimmed = raw.trim();
                if (trimmed.isEmpty()) continue;
                String normalized = SpeechInputNormalizer.normalize(trimmed);
                String scored = normalized.isEmpty() ? trimmed : normalized;
                double score = SpeechInputNormalizer.qualityScore(scored);
                if (score > bestScore) {
                    bestScore = score;
                    best = trimmed;
                }
            }
            return best != null ? best : hypotheses.get(0).trim();
        }

        @Override public void onError(int error) {
            setListeningActive(false);
            pushToTalkMode = false;
            releaseSttScoAfterListen();
            if (error != SpeechRecognizer.ERROR_CLIENT
                    && error != SpeechRecognizer.ERROR_NO_MATCH
                    && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                Toast.makeText(appContext, speechErrorLabel(error), Toast.LENGTH_SHORT).show();
            }
            notifyListenFailed();
        }

        @Override public void onReadyForSpeech(Bundle params) {}
        @Override public void onBeginningOfSpeech() {}
        @Override public void onRmsChanged(float rmsdB) {}
        @Override public void onBufferReceived(byte[] buffer) {}
        @Override public void onEndOfSpeech() { setListeningActive(false); }
        @Override public void onPartialResults(Bundle partialResults) {}
        @Override public void onEvent(int eventType, Bundle params) {}
    }

    private static String speechErrorLabel(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_AUDIO:
                return "Micro indisponible";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return "Permission micro refusée";
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                return "Reconnaissance vocale : pas de réseau";
            case SpeechRecognizer.ERROR_SERVER:
                return "Service vocal indisponible";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                return "Micro occupé, réessaie";
            default:
                return "Erreur vocale";
        }
    }
}
