package com.pegasuscorp.orbe;

import android.util.Log;

import com.google.mlkit.common.MlKitException;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.common.model.RemoteModelManager;
import com.google.mlkit.vision.digitalink.DigitalInkRecognition;
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel;
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier;
import com.google.mlkit.vision.digitalink.DigitalInkRecognizer;
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions;
import com.google.mlkit.vision.digitalink.Ink;

/**
 * Gère la reconnaissance de tracé de lettres via ML Kit (modèle fr-FR).
 */
public class DigitalInkManager {
    private static final String TAG = "DigitalInkManager";
    private static volatile DigitalInkManager instance;

    public static DigitalInkManager getInstance() {
        if (instance == null) {
            synchronized (DigitalInkManager.class) {
                if (instance == null) instance = new DigitalInkManager();
            }
        }
        return instance;
    }

    public enum ModelState { LOADING, READY, ERROR }

    public interface ModelStateListener {
        void onModelStateChanged(ModelState state, String message);
    }

    public interface RecognitionListener {
        void onRecognized(String text);
        void onRecognitionFailed(String reason);
    }

    private DigitalInkRecognizer recognizer;
    private DigitalInkRecognitionModel model;
    private ModelState state = ModelState.LOADING;
    private ModelStateListener stateListener;

    private DigitalInkManager() {
        DigitalInkRecognitionModelIdentifier modelIdentifier;
        try {
            modelIdentifier = DigitalInkRecognitionModelIdentifier.fromLanguageTag("fr-FR");
        } catch (MlKitException e) {
            Log.e(TAG, "Erreur identification modèle", e);
            setState(ModelState.ERROR, "Modèle français indisponible");
            return;
        }

        model = DigitalInkRecognitionModel.builder(modelIdentifier).build();
        RemoteModelManager remoteModelManager = RemoteModelManager.getInstance();
        setState(ModelState.LOADING, "Chargement du modèle…");

        remoteModelManager.isModelDownloaded(model)
                .addOnSuccessListener(downloaded -> {
                    if (downloaded) {
                        initRecognizer();
                    } else {
                        downloadModel();
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Erreur vérification modèle", e);
                    setState(ModelState.ERROR, "Impossible de vérifier le modèle");
                });
    }

    public void setModelStateListener(ModelStateListener listener) {
        stateListener = listener;
        if (listener != null) {
            listener.onModelStateChanged(state, stateMessageFor(state));
        }
    }

    public ModelState getState() {
        return state;
    }

    public boolean isReady() {
        return state == ModelState.READY && recognizer != null;
    }

    private void downloadModel() {
        RemoteModelManager.getInstance()
                .download(model, new DownloadConditions.Builder().build())
                .addOnSuccessListener(aVoid -> initRecognizer())
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Erreur téléchargement modèle", e);
                    setState(ModelState.ERROR, "Téléchargement échoué — vérifie le réseau");
                });
    }

    private void initRecognizer() {
        recognizer = DigitalInkRecognition.getClient(
                DigitalInkRecognizerOptions.builder(model).build());
        setState(ModelState.READY, "Tracé de lettres prêt");
        Log.i(TAG, "Modèle prêt");
    }

    private void setState(ModelState newState, String message) {
        state = newState;
        if (stateListener != null) {
            stateListener.onModelStateChanged(newState, message);
        }
    }

    private String stateMessageFor(ModelState s) {
        switch (s) {
            case READY:   return "Tracé de lettres prêt";
            case ERROR:   return "Modèle indisponible";
            default:      return "Chargement du modèle…";
        }
    }

    public void recognize(Ink ink, RecognitionListener listener) {
        if (recognizer == null || state != ModelState.READY) {
            String reason = state == ModelState.LOADING
                    ? "Modèle en cours de chargement…"
                    : "Modèle indisponible";
            if (listener != null) listener.onRecognitionFailed(reason);
            return;
        }

        recognizer.recognize(ink)
                .addOnSuccessListener(result -> {
                    if (listener == null) return;
                    if (!result.getCandidates().isEmpty()) {
                        listener.onRecognized(result.getCandidates().get(0).getText());
                    } else {
                        listener.onRecognitionFailed("Lettre non reconnue");
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Erreur reconnaissance", e);
                    if (listener != null) {
                        listener.onRecognitionFailed("Erreur de reconnaissance");
                    }
                });
    }
}
