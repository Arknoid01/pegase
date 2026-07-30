package com.pegasuscorp.orbe.voice;

import android.content.Context;

import java.io.File;

/**
 * Modèle ECAPA / WeSpeaker pour la reconnaissance du locuteur (sherpa-onnx).
 */
public final class SpeakerModelStore {

    private static final String MODEL_NAME = "wespeaker_en_voxceleb_resnet34_LM.onnx";
    private static final String MODEL_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/"
                    + MODEL_NAME;

    private SpeakerModelStore() {}

    public static File modelFile(Context context) {
        File dir = new File(context.getApplicationContext().getFilesDir(), "speaker");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, MODEL_NAME);
    }

    public static boolean isModelReady(Context context) {
        File f = modelFile(context);
        return f.exists() && f.length() > 1_000_000;
    }

    public static String getModelUrl() {
        return MODEL_URL;
    }

    public static String statusLabel(Context context) {
        return isModelReady(context) ? "Modèle locuteur installé" : "Modèle locuteur non installé";
    }
}
