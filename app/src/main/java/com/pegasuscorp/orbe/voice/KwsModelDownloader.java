package com.pegasuscorp.orbe.voice;

import android.content.Context;
import android.util.Log;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Télécharge et extrait le pack KWS mobile GigaSpeech (~15 Mo).
 */
public final class KwsModelDownloader {

    public interface Callback {
        void onProgress(int percent);
        void onComplete(boolean success, String message);
    }

    private static final String TAG = "KwsModelDl";
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean DOWNLOADING = new AtomicBoolean(false);

    private static final Set<String> KEEP = new HashSet<>();
    static {
        KEEP.add(KwsModelStore.ENCODER);
        KEEP.add(KwsModelStore.DECODER);
        KEEP.add(KwsModelStore.JOINER);
        KEEP.add(KwsModelStore.TOKENS);
    }

    private KwsModelDownloader() {}

    public static boolean isDownloading() {
        return DOWNLOADING.get();
    }

    public static void download(Context context, Callback callback) {
        if (KwsModelStore.isModelReady(context)) {
            callback.onComplete(true, "Modèle wake déjà installé");
            return;
        }
        if (!DOWNLOADING.compareAndSet(false, true)) {
            callback.onComplete(false, "Téléchargement déjà en cours");
            return;
        }
        Context app = context.getApplicationContext();
        IO.execute(() -> {
            File archive = null;
            try {
                File dir = KwsModelStore.modelDir(app);
                archive = new File(dir, KwsModelStore.ARCHIVE_NAME);
                callback.onProgress(3);
                downloadUrl(KwsModelStore.ARCHIVE_URL, archive, callback);
                callback.onProgress(78);
                extractNeeded(archive, dir);
                callback.onProgress(92);
                KwsModelStore.writeKeywords(app);
                // Nettoyage archive + ancien encodeur int8 mobile (crash Reshape)
                //noinspection ResultOfMethodCallIgnored
                archive.delete();
                File int8Enc = new File(dir, "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx");
                File int8Join = new File(dir, "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx");
                //noinspection ResultOfMethodCallIgnored
                int8Enc.delete();
                //noinspection ResultOfMethodCallIgnored
                int8Join.delete();
                KwsModelStore.logModelIdentity(app);
                if (!KwsModelStore.isModelReady(app)) {
                    throw new IllegalStateException("Fichiers KWS incomplets après extraction");
                }
                callback.onProgress(100);
                callback.onComplete(true, "Wake local Sherpa installé");
            } catch (Exception e) {
                Log.e(TAG, "download failed", e);
                if (archive != null) {
                    //noinspection ResultOfMethodCallIgnored
                    archive.delete();
                }
                callback.onComplete(false, "Échec wake local : " + e.getMessage());
            } finally {
                DOWNLOADING.set(false);
            }
        });
    }

    private static void downloadUrl(String urlStr, File dest, Callback callback) throws Exception {
        File parent = dest.getParentFile();
        if (parent != null) parent.mkdirs();
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
                    int pct = 5 + (int) (70L * done / total);
                    callback.onProgress(Math.min(75, pct));
                }
            }
        }
    }

    private static void extractNeeded(File archive, File destDir) throws Exception {
        try (InputStream fin = new FileInputStream(archive);
             BufferedInputStream bin = new BufferedInputStream(fin);
             BZip2CompressorInputStream bzIn = new BZip2CompressorInputStream(bin);
             TarArchiveInputStream tarIn = new TarArchiveInputStream(bzIn)) {
            TarArchiveEntry entry;
            while ((entry = tarIn.getNextTarEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                int slash = name.lastIndexOf('/');
                String base = slash >= 0 ? name.substring(slash + 1) : name;
                if (!KEEP.contains(base)) continue;
                File out = new File(destDir, base);
                try (OutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = tarIn.read(buf)) != -1) {
                        os.write(buf, 0, n);
                    }
                }
            }
        }
        // Ancien pack mobile int8 — plus utilisé
        File int8Enc = new File(destDir, "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx");
        if (int8Enc.exists()) {
            //noinspection ResultOfMethodCallIgnored
            int8Enc.delete();
        }
    }
}
