package com.pegasuscorp.orbe.bureau;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import androidx.core.app.ActivityCompat;

import com.pegasuscorp.orbe.voice.SpeechInputNormalizer;
import com.pegasuscorp.orbe.voice.VoiceMuteStore;

import java.util.ArrayList;

/**
 * Micro du bureau — PUSH-TO-TALK.
 *
 * Avant : le recognizer se relançait sur toutes les erreurs, y compris NO_MATCH,
 * donc en boucle infinie tant que le bureau était ouvert (batterie + throttling Google).
 * Maintenant : une écoute = un appui. Un seul retry, uniquement si le micro était occupé.
 */
public final class BureauMic {

    private static final int REQ_MIC = 901;

    public interface Callback {
        void onTranscript(String transcript);
        void onListeningReady();
        void onListenFailed(int errorCode, String message);
    }

    private final Activity activity;
    private final Callback callback;
    private final Handler main = new Handler(Looper.getMainLooper());

    private SpeechRecognizer recognizer;
    private boolean active;          // une écoute est en cours
    private boolean retriedOnce;
    private Runnable retryRunnable;

    public BureauMic(Activity activity, Callback callback) {
        this.activity = activity;
        this.callback = callback;
    }

    public boolean isActive() { return active; }

    public void startListening() {
        cancelRetry();
        if (active) return;

        if (VoiceMuteStore.isMuted(activity)) {
            callback.onListenFailed(-1, "Micro coupé — réactive-le dans le tiroir.");
            return;
        }
        if (ActivityCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(activity,
                    new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            callback.onListenFailed(-2, "Permission micro requise.");
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
            callback.onListenFailed(-3, "Reconnaissance vocale indisponible.");
            return;
        }
        ensureRecognizer();
        if (recognizer == null) {
            callback.onListenFailed(-4, "Impossible d'initialiser le micro.");
            return;
        }

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                .putExtra("android.speech.extra.SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLISECONDS", 1800)
                .putExtra("android.speech.extra.SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLISECONDS", 1500)
                .putExtra("android.speech.extra.SPEECH_INPUT_MINIMUM_LENGTH_MILLISECONDS", 400);
        try {
            active = true;
            recognizer.startListening(intent);
        } catch (RuntimeException ex) {
            active = false;
            recreateRecognizer();
            retryOnce();
        }
    }

    public void stopListening() {
        cancelRetry();
        active = false;
        retriedOnce = false;
        if (recognizer != null) {
            try { recognizer.cancel(); } catch (Exception ignored) {}
        }
    }

    public void release() {
        stopListening();
        destroyRecognizer();
    }

    private void ensureRecognizer() {
        if (recognizer != null) return;
        recognizer = SpeechRecognizer.createSpeechRecognizer(activity);
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) {
                retriedOnce = false;
                callback.onListeningReady();
            }
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() {}

            @Override public void onError(int error) {
                active = false;
                // Micro occupé / client : UN seul nouvel essai, jamais de boucle.
                if ((error == SpeechRecognizer.ERROR_CLIENT
                        || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) && !retriedOnce) {
                    retriedOnce = true;
                    recreateRecognizer();
                    retryOnce();
                    return;
                }
                if (error == SpeechRecognizer.ERROR_NO_MATCH
                        || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    callback.onListenFailed(error, "");   // silence : on ne relance pas
                } else {
                    callback.onListenFailed(error, errorLabel(error));
                }
            }

            @Override public void onResults(Bundle results) {
                active = false;
                String transcript = pickBest(
                        results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION));
                if (!transcript.isEmpty()) callback.onTranscript(transcript);
                else callback.onListenFailed(SpeechRecognizer.ERROR_NO_MATCH, "");
            }

            @Override public void onPartialResults(Bundle partialResults) {}
            @Override public void onEvent(int eventType, Bundle params) {}
        });
    }

    private void retryOnce() {
        cancelRetry();
        retryRunnable = this::startListening;
        main.postDelayed(retryRunnable, 700);
    }

    private void cancelRetry() {
        if (retryRunnable != null) {
            main.removeCallbacks(retryRunnable);
            retryRunnable = null;
        }
    }

    private void recreateRecognizer() {
        destroyRecognizer();
        ensureRecognizer();
    }

    private void destroyRecognizer() {
        if (recognizer != null) {
            try { recognizer.destroy(); } catch (Exception ignored) {}
            recognizer = null;
        }
    }

    private static String pickBest(ArrayList<String> hypotheses) {
        if (hypotheses == null || hypotheses.isEmpty()) return "";
        String best = null;
        double bestScore = -1;
        for (String raw : hypotheses) {
            if (raw == null) continue;
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) continue;
            String normalized = SpeechInputNormalizer.normalize(trimmed);
            double score = SpeechInputNormalizer.qualityScore(
                    normalized.isEmpty() ? trimmed : normalized);
            if (score > bestScore) { bestScore = score; best = trimmed; }
        }
        return best != null ? best : hypotheses.get(0).trim();
    }

    private static String errorLabel(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_AUDIO: return "Problème audio — vérifie le micro.";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "Permission micro refusée.";
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "Pas de réseau pour la reconnaissance vocale.";
            case SpeechRecognizer.ERROR_SERVER: return "Service vocal Google indisponible.";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: return "Micro occupé par une autre app.";
            case SpeechRecognizer.ERROR_CLIENT: return "Micro interrompu.";
            default: return "Erreur micro (" + error + ").";
        }
    }
}
