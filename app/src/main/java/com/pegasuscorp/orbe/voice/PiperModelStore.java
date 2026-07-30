package com.pegasuscorp.orbe.voice;

import android.content.Context;

import java.io.File;

/**
 * Chemins et état du modèle vocal Piper (sherpa-onnx).
 */
public final class PiperModelStore {

    /** Catalogue des voix Piper disponibles au téléchargement. */
    public static final class Voice {
        public final String id;
        public final String label;
        public final String dirName;
        public final String onnxName;
        public final String hfBase;
        public final String manifestAsset;

        Voice(String id, String label, String dirName, String onnxName,
              String hfBase, String manifestAsset) {
            this.id = id;
            this.label = label;
            this.dirName = dirName;
            this.onnxName = onnxName;
            this.hfBase = hfBase;
            this.manifestAsset = manifestAsset;
        }
    }

    public static final Voice VOICE_UPMC = new Voice(
            "upmc",
            "Jessica — France (UPMC)",
            "vits-piper-fr_FR-upmc-medium",
            "fr_FR-upmc-medium.onnx",
            "https://huggingface.co/csukuangfj/vits-piper-fr_FR-upmc-medium/resolve/main/",
            "piper/upmc-files.json");

    public static final Voice VOICE_SIWIS = new Voice(
            "siwis",
            "Siwis — Suisse (défaut)",
            "vits-piper-fr_FR-siwis-medium",
            "fr_FR-siwis-medium.onnx",
            "https://huggingface.co/csukuangfj/vits-piper-fr_FR-siwis-medium/resolve/main/",
            "piper/files.json");

    public static final Voice[] ALL_VOICES = { VOICE_UPMC, VOICE_SIWIS };

    // Compatibilité ascendante
    public static final String DIR_NAME = VOICE_SIWIS.dirName;
    public static final String ONNX_NAME = VOICE_SIWIS.onnxName;
    public static final String HF_BASE = VOICE_SIWIS.hfBase;

    private static final String PREFS = "orbe_piper";
    private static final String KEY_MODEL_DIR = "piper_model_dir";
    private static final String KEY_USE_PIPER = "use_piper";
    private static final String KEY_DOWNLOAD_OFFERED = "download_offered";
    private static final String KEY_SELECTED_VOICE = "selected_voice";
    private static volatile String downloadStatus;

    private PiperModelStore() {}

    public static Voice getSelectedVoice(Context context) {
        String id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_SELECTED_VOICE, VOICE_UPMC.id);
        for (Voice v : ALL_VOICES) {
            if (v.id.equals(id)) return v;
        }
        return VOICE_UPMC;
    }

    public static void setSelectedVoice(Context context, Voice voice) {
        android.content.SharedPreferences.Editor ed =
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        ed.putString(KEY_SELECTED_VOICE, voice.id);
        if (isVoiceReady(context, voice)) {
            ed.putString(KEY_MODEL_DIR, voiceDir(context, voice).getAbsolutePath());
        }
        ed.apply();
    }

    public static File voiceDir(Context context, Voice voice) {
        return new File(piperRoot(context), voice.dirName);
    }

    public static boolean isVoiceReady(Context context, Voice voice) {
        File dir = voiceDir(context, voice);
        File onnx = new File(dir, voice.onnxName);
        File tokens = new File(dir, "tokens.txt");
        File espeak = new File(dir, "espeak-ng-data");
        return onnx.exists() && tokens.exists() && espeak.isDirectory();
    }

    public static float getSpeechSpeed(Context context) {
        return SpeechRulesStore.getInstance(context).getSpeed();
    }

    public static void setSpeechSpeed(Context context, float speed) {
        SpeechRulesStore.getInstance(context).setSpeed(speed);
    }

    public static boolean usePiper(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_USE_PIPER, true);
    }

    public static void setUsePiper(Context context, boolean use) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_USE_PIPER, use).apply();
    }

    public static File piperRoot(Context context) {
        File dir = new File(context.getFilesDir(), "piper");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public static File defaultModelDir(Context context) {
        return voiceDir(context, getSelectedVoice(context));
    }

    public static void setDownloadStatus(String status) {
        downloadStatus = status;
    }

    public static void clearDownloadStatus() {
        downloadStatus = null;
    }

    public static boolean wasDownloadOffered(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_DOWNLOAD_OFFERED, false);
    }

    public static void markDownloadOffered(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_DOWNLOAD_OFFERED, true).apply();
    }

    public static String getModelDirPath(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_MODEL_DIR, "");
    }

    public static void setModelDirPath(Context context, String path) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_MODEL_DIR, path == null ? "" : path).apply();
    }

    public static File resolveModelDir(Context context) {
        // 1. Voix sélectionnée (priorité — évite de rester bloqué sur un ancien import)
        Voice selected = getSelectedVoice(context);
        File selectedDir = voiceDir(context, selected);
        if (isVoiceReady(context, selected)) return selectedDir;

        // 2. Chemin personnalisé (import manuel)
        String custom = getModelDirPath(context);
        if (custom != null && !custom.isEmpty()) {
            File f = new File(custom);
            if (f.exists() && isDirModelReady(f)) return f;
        }

        // 3. N'importe quelle voix installée
        for (Voice v : ALL_VOICES) {
            File d = voiceDir(context, v);
            if (isVoiceReady(context, v)) return d;
        }
        return null;
    }

    private static boolean isDirModelReady(File dir) {
        return findOnnx(dir) != null
                && new File(dir, "tokens.txt").exists()
                && new File(dir, "espeak-ng-data").isDirectory();
    }

    public static boolean isModelReady(Context context) {
        return resolveModelDir(context) != null;
    }

    public static File findOnnx(File dir) {
        if (dir == null) return null;
        for (Voice v : ALL_VOICES) {
            if (dir.getName().equals(v.dirName)) {
                File expected = new File(dir, v.onnxName);
                if (expected.exists()) return expected;
            }
        }
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (f.getName().endsWith(".onnx")) return f;
        }
        return null;
    }

    public static String statusLabel(Context context) {
        if (downloadStatus != null && !downloadStatus.isEmpty()) return downloadStatus;
        if (!usePiper(context)) return "TTS Android (Piper désactivé)";
        if (isModelReady(context)) {
            return "Piper prêt — " + getSelectedVoice(context).label;
        }
        return "Piper : modèle manquant (TTS Android)";
    }

    /** Met à jour le libellé si la lib native Piper est indisponible. */
    public static String statusLabel(Context context, boolean nativeBroken) {
        if (nativeBroken) return "Piper indisponible (lib native) — TTS Android";
        return statusLabel(context);
    }
}
