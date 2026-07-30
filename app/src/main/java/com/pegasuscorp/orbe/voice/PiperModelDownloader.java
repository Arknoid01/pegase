package com.pegasuscorp.orbe.voice;

import android.content.Context;

import org.json.JSONArray;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Télécharge le modèle Piper français depuis Hugging Face (~75 Mo, une seule fois).
 */
public final class PiperModelDownloader {

    public interface Callback {
        void onProgress(int percent, String fileName);
        void onComplete(PiperModelImporter.Result result);
    }

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean DOWNLOADING = new AtomicBoolean(false);

    private PiperModelDownloader() {}

    public static boolean isDownloading() {
        return DOWNLOADING.get();
    }

    public static void download(Context context, Callback callback) {
        download(context, PiperModelStore.getSelectedVoice(context), callback);
    }

    public static void download(Context context, PiperModelStore.Voice voice, Callback callback) {
        if (PiperModelStore.isVoiceReady(context, voice)) {
            callback.onComplete(PiperModelImporter.Result.ok(voice.label + " déjà installée"));
            return;
        }
        if (!DOWNLOADING.compareAndSet(false, true)) {
            callback.onComplete(PiperModelImporter.Result.fail("Téléchargement déjà en cours"));
            return;
        }
        Context app = context.getApplicationContext();
        IO.execute(() -> {
            PiperModelImporter.Result result;
            try {
                result = runDownload(app, voice, callback);
            } catch (Exception e) {
                result = PiperModelImporter.Result.fail("Téléchargement échoué : " + e.getMessage());
            } finally {
                DOWNLOADING.set(false);
                PiperModelStore.clearDownloadStatus();
            }
            PiperModelImporter.Result finalResult = result;
            callback.onComplete(finalResult);
        });
    }

    private static PiperModelImporter.Result runDownload(Context app,
            PiperModelStore.Voice voice, Callback callback) throws Exception {
        List<String> files = loadManifest(app, voice.manifestAsset);
        File destDir = PiperModelStore.voiceDir(app, voice);
        PiperModelImporter.prepareDest(destDir);

        for (int i = 0; i < files.size(); i++) {
            String path = files.get(i);
            int percent = (int) ((i + 1) * 100f / files.size());
            String label = "Téléchargement Piper " + percent + "%";
            PiperModelStore.setDownloadStatus(label);
            callback.onProgress(percent, path);
            downloadFile(voice.hfBase, path, new File(destDir, path));
        }
        PiperModelStore.setSelectedVoice(app, voice);
        return PiperModelImporter.finalizeInstall(app, destDir);
    }

    private static List<String> loadManifest(Context context, String assetPath) throws Exception {
        try (InputStream in = context.getAssets().open(assetPath);
             BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            JSONArray arr = new JSONArray(sb.toString());
            List<String> files = new ArrayList<>(arr.length());
            for (int i = 0; i < arr.length(); i++) {
                files.add(arr.getString(i));
            }
            return files;
        }
    }

    private static void downloadFile(String hfBase, String path, File dest) throws Exception {
        URL url = new URL(hfBase + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(30_000);
        conn.setReadTimeout(180_000);
        conn.setRequestProperty("User-Agent", "Orbe-Pegase/1.0");
        conn.connect();
        int code = conn.getResponseCode();
        if (code >= 400) {
            throw new IllegalStateException("HTTP " + code + " pour " + path);
        }
        File parent = dest.getParentFile();
        if (parent != null) parent.mkdirs();
        try (InputStream in = new BufferedInputStream(conn.getInputStream());
             OutputStream out = new BufferedOutputStream(new FileOutputStream(dest))) {
            byte[] buf = new byte[65536];
            int read;
            while ((read = in.read(buf)) != -1) {
                out.write(buf, 0, read);
            }
        } finally {
            conn.disconnect();
        }
    }
}
