package com.pegasuscorp.orbe.voice;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Télécharge le modèle speaker recognition sherpa-onnx (~30 Mo).
 */
public final class SpeakerModelDownloader {

    public interface Callback {
        void onProgress(int percent);
        void onComplete(boolean success, String message);
    }

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean DOWNLOADING = new AtomicBoolean(false);

    private SpeakerModelDownloader() {}

    public static void download(Context context, Callback callback) {
        if (SpeakerModelStore.isModelReady(context)) {
            callback.onComplete(true, "Modèle déjà installé");
            return;
        }
        if (!DOWNLOADING.compareAndSet(false, true)) {
            callback.onComplete(false, "Téléchargement déjà en cours");
            return;
        }
        Context app = context.getApplicationContext();
        IO.execute(() -> {
            try {
                File dest = SpeakerModelStore.modelFile(app);
                File parent = dest.getParentFile();
                if (parent != null) parent.mkdirs();
                callback.onProgress(5);
                downloadUrl(SpeakerModelStore.getModelUrl(), dest, callback);
                callback.onProgress(100);
                callback.onComplete(true, "Modèle locuteur installé");
            } catch (Exception e) {
                callback.onComplete(false, "Échec : " + e.getMessage());
            } finally {
                DOWNLOADING.set(false);
            }
        });
    }

    private static void downloadUrl(String urlStr, File dest, Callback callback) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(30_000);
        conn.setReadTimeout(300_000);
        conn.setRequestProperty("User-Agent", "Orbe-Pegase/1.0");
        conn.connect();
        if (conn.getResponseCode() >= 400) {
            throw new IllegalStateException("HTTP " + conn.getResponseCode());
        }
        int total = conn.getContentLength();
        try (InputStream in = new BufferedInputStream(conn.getInputStream());
             OutputStream out = new BufferedOutputStream(new FileOutputStream(dest))) {
            byte[] buf = new byte[8192];
            int read;
            int done = 0;
            while ((read = in.read(buf)) != -1) {
                out.write(buf, 0, read);
                done += read;
                if (total > 0) {
                    int pct = 10 + (int) (85L * done / total);
                    callback.onProgress(Math.min(99, pct));
                }
            }
        }
    }
}
