package com.pegasuscorp.orbe.voice;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Échantillons + modèles openWakeWord sous {@code files/wake_oww/}.
 * <ul>
 *   <li>{@code samples/hey_pegase_XXX.wav} — clips d'entraînement</li>
 *   <li>{@code melspectrogram.onnx}, {@code embedding_model.onnx} — backbone partagé</li>
 *   <li>{@code hey_pegase.onnx} — classifieur custom</li>
 * </ul>
 */
public final class WakeOwwStore {

    private static final String TAG = "WakeOwwStore";
    public static final String DIR = "wake_oww";
    public static final String SAMPLES_DIR = "samples";
    public static final String FILENAME_PREFIX = "hey_pegase";
    public static final String MEL_ONNX = "melspectrogram.onnx";
    public static final String EMBED_ONNX = "embedding_model.onnx";
    public static final String CLASSIFIER_ONNX = "hey_pegase.onnx";

    private static final String PREFS = "wake_oww_prefs";
    /** Opt-in : sans ce flag, VoiceService reste sur Sherpa même si le modèle OWW est prêt. */
    private static final String KEY_PREFER_CUSTOM = "prefer_custom_wake";

    /** Backbone openWakeWord (Apache-2.0) — release upstream. */
    public static final String MEL_URL =
            "https://github.com/dscripka/openWakeWord/releases/download/v0.5.1/melspectrogram.onnx";
    public static final String EMBED_URL =
            "https://github.com/dscripka/openWakeWord/releases/download/v0.5.1/embedding_model.onnx";

    private static final long MIN_MEL_BYTES = 50_000L;
    private static final long MIN_EMBED_BYTES = 500_000L;
    private static final long MIN_CLASSIFIER_BYTES = 10_000L;

    private WakeOwwStore() {}

    public static File rootDir(Context context) {
        File dir = new File(context.getApplicationContext().getFilesDir(), DIR);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public static File samplesDir(Context context) {
        File dir = new File(rootDir(context), SAMPLES_DIR);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public static File melFile(Context context) {
        return new File(rootDir(context), MEL_ONNX);
    }

    public static File embedFile(Context context) {
        return new File(rootDir(context), EMBED_ONNX);
    }

    public static File classifierFile(Context context) {
        return new File(rootDir(context), CLASSIFIER_ONNX);
    }

    public static boolean isBackboneReady(Context context) {
        return melFile(context).isFile() && melFile(context).length() >= MIN_MEL_BYTES
                && embedFile(context).isFile() && embedFile(context).length() >= MIN_EMBED_BYTES;
    }

    public static boolean isClassifierReady(Context context) {
        return classifierFile(context).isFile()
                && classifierFile(context).length() >= MIN_CLASSIFIER_BYTES;
    }

    /** Prêt pour détection openWakeWord (backbone + classifieur). */
    public static boolean isModelReady(Context context) {
        return isBackboneReady(context) && isClassifierReady(context);
    }

    /**
     * True si openWakeWord doit être utilisé.
     * Si {@code hey_pegase.onnx} + backbone sont prêts → OWW (sauf opt-out explicite).
     * Sans modèle custom → Sherpa.
     */
    public static boolean preferCustomWake(Context context) {
        if (context == null) return false;
        if (!isModelReady(context)) return false;
        SharedPreferences p = prefs(context);
        // Modèle pushé via adb / import : activer OWW par défaut.
        if (!p.contains(KEY_PREFER_CUSTOM)) return true;
        return p.getBoolean(KEY_PREFER_CUSTOM, true);
    }

    public static void setPreferCustomWake(Context context, boolean prefer) {
        if (context == null) return;
        prefs(context).edit().putBoolean(KEY_PREFER_CUSTOM, prefer).apply();
        Log.i(TAG, "prefer_custom_wake=" + prefer);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String statusLabel(Context context) {
        int n = countSamples(context);
        boolean oww = isModelReady(context);
        if (oww) {
            String active = preferCustomWake(context) ? "actif" : "installé (Sherpa par défaut)";
            return "openWakeWord " + active + " · " + n + " clip(s) enregistré(s)";
        }
        if (isClassifierReady(context) && !isBackboneReady(context)) {
            return "Classifieur présent — télécharge le backbone (~1,5 Mo) · "
                    + n + " clip(s)";
        }
        if (isBackboneReady(context)) {
            return "Backbone OK — importe hey_pegase.onnx · " + n + " clip(s)";
        }
        return "openWakeWord non installé · " + n + " clip(s) d'entraînement";
    }

    public static int countSamples(Context context) {
        File[] files = listSampleFiles(context);
        return files == null ? 0 : files.length;
    }

    /** Prochain index (1-based) pour {@code hey_pegase_XXX.wav}. */
    public static int nextSampleIndex(Context context) {
        File[] files = listSampleFiles(context);
        if (files == null || files.length == 0) return 1;
        int max = 0;
        for (File f : files) {
            String name = f.getName();
            // hey_pegase_001.wav
            int us = name.lastIndexOf('_');
            int dot = name.lastIndexOf('.');
            if (us < 0 || dot <= us) continue;
            try {
                int idx = Integer.parseInt(name.substring(us + 1, dot));
                if (idx > max) max = idx;
            } catch (NumberFormatException ignored) {}
        }
        return max + 1;
    }

    public static File sampleFile(Context context, int index) {
        String name = String.format(Locale.US, "%s_%03d.wav", FILENAME_PREFIX, index);
        return new File(samplesDir(context), name);
    }

    public static File[] listSampleFiles(Context context) {
        File dir = samplesDir(context);
        File[] files = dir.listFiles((d, name) ->
                name.startsWith(FILENAME_PREFIX) && name.endsWith(".wav"));
        if (files != null) {
            Arrays.sort(files, Comparator.comparing(File::getName));
        }
        return files;
    }

    public static void clearSamples(Context context) {
        File[] files = listSampleFiles(context);
        if (files == null) return;
        for (File f : files) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    /** Copie un stream (SAF) vers {@link #CLASSIFIER_ONNX}. */
    public static void importClassifier(Context context, InputStream in) throws Exception {
        File dest = classifierFile(context);
        File tmp = new File(dest.getParentFile(), CLASSIFIER_ONNX + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (n > 0) out.write(buf, 0, n);
            }
            out.flush();
        }
        if (tmp.length() < MIN_CLASSIFIER_BYTES) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IllegalStateException("Fichier trop petit pour un modèle ONNX");
        }
        if (dest.exists() && !dest.delete()) {
            Log.w(TAG, "could not delete old classifier");
        }
        if (!tmp.renameTo(dest)) {
            throw new IllegalStateException("Impossible d'installer hey_pegase.onnx");
        }
        setPreferCustomWake(context, true);
        Log.i(TAG, "classifier imported bytes=" + dest.length());
    }

    /** Zip des WAV samples → fichier temporaire partageable. */
    public static File zipSamples(Context context) throws Exception {
        File[] files = listSampleFiles(context);
        if (files == null || files.length == 0) {
            throw new IllegalStateException("Aucun échantillon à exporter");
        }
        File zip = new File(rootDir(context), "hey_pegase_samples.zip");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zip))) {
            byte[] buf = new byte[8192];
            for (File f : files) {
                ZipEntry entry = new ZipEntry(f.getName());
                zos.putNextEntry(entry);
                try (FileInputStream in = new FileInputStream(f)) {
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        zos.write(buf, 0, n);
                    }
                }
                zos.closeEntry();
            }
        }
        return zip;
    }

    public static List<String> sampleNames(Context context) {
        List<String> names = new ArrayList<>();
        File[] files = listSampleFiles(context);
        if (files == null) return names;
        for (File f : files) names.add(f.getName());
        return names;
    }
}
