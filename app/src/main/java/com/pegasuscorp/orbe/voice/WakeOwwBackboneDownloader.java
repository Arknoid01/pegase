package com.pegasuscorp.orbe.voice;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Télécharge melspectrogram.onnx + embedding_model.onnx (backbone openWakeWord).
 */
public final class WakeOwwBackboneDownloader {

    public interface Callback {
        void onComplete(boolean success, String message);
    }

    private static final String TAG = "WakeOwwBackbone";
    private static final AtomicBoolean downloading = new AtomicBoolean(false);

    private WakeOwwBackboneDownloader() {}

    public static boolean isDownloading() {
        return downloading.get();
    }

    public static void download(Context context, Callback callback) {
        if (!downloading.compareAndSet(false, true)) {
            if (callback != null) callback.onComplete(false, "Téléchargement déjà en cours");
            return;
        }
        Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                downloadOne(WakeOwwStore.MEL_URL, WakeOwwStore.melFile(app));
                downloadOne(WakeOwwStore.EMBED_URL, WakeOwwStore.embedFile(app));
                boolean ok = WakeOwwStore.isBackboneReady(app);
                if (callback != null) {
                    callback.onComplete(ok, ok
                            ? "Backbone openWakeWord installé"
                            : "Téléchargement incomplet");
                }
            } catch (Exception e) {
                Log.e(TAG, "download failed", e);
                if (callback != null) {
                    callback.onComplete(false, "Échec : " + e.getMessage());
                }
            } finally {
                downloading.set(false);
            }
        }, "oww-backbone-dl").start();
    }

    private static void downloadOne(String urlStr, File dest) throws Exception {
        File parent = dest.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        File tmp = new File(dest.getParentFile(), dest.getName() + ".tmp");
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(30_000);
        conn.setReadTimeout(120_000);
        conn.setInstanceFollowRedirects(true);
        try (InputStream in = conn.getInputStream();
             FileOutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (n > 0) out.write(buf, 0, n);
            }
            out.flush();
        } finally {
            conn.disconnect();
        }
        if (dest.exists() && !dest.delete()) {
            Log.w(TAG, "could not delete " + dest.getName());
        }
        if (!tmp.renameTo(dest)) {
            throw new IllegalStateException("rename failed for " + dest.getName());
        }
        Log.i(TAG, "saved " + dest.getName() + " bytes=" + dest.length());
    }
}
