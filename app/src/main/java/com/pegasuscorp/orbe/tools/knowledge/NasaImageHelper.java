package com.pegasuscorp.orbe.tools.knowledge;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.pegasuscorp.orbe.NasaImagePreviewActivity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Télécharge la photo NASA APOD puis l'affiche via {@link NasaImagePreviewActivity}.
 */
public final class NasaImageHelper {

    public interface LoadCallback {
        void onLoaded(Bitmap bitmap);
        void onError(String message);
    }

    private static final String TAG = "NasaImage";
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private NasaImageHelper() {}

    public static void show(Context context, String reply) {
        if (context == null || reply == null) return;
        String imageUrl = NasaReplyHelper.extractImageUrl(reply);
        showImageUrl(context, imageUrl);
    }

    /** Affiche une photo NASA depuis une URL (sans préfixe {@code NASA_IMAGE:}). */
    public static void showImageUrl(Context context, String imageUrl) {
        if (context == null || imageUrl == null || imageUrl.isEmpty()) return;

        Context app = context.getApplicationContext();
        Toast.makeText(app, "Téléchargement photo NASA…", Toast.LENGTH_SHORT).show();

        final Activity activity = context instanceof Activity ? (Activity) context : null;

        IO.execute(() -> {
            File cached = downloadToCache(app, imageUrl);
            MAIN.post(() -> {
                if (cached == null) {
                    Toast.makeText(app, "Impossible de télécharger la photo NASA.",
                            Toast.LENGTH_LONG).show();
                    if (activity != null && !activity.isFinishing()) {
                        showImageDialog(activity, "NASA_IMAGE:" + imageUrl + "::");
                    }
                    return;
                }
                // Toujours via Application + NEW_TASK : fiable depuis Fragment / voix /
                // thread UI après pause de MainActivity.
                NasaImagePreviewActivity.open(app, cached.getAbsolutePath());
            });
        });
    }

    public static void showImageDialog(Activity activity, String reply) {
        if (activity == null || reply == null) return;
        String imageUrl = NasaReplyHelper.extractImageUrl(reply);
        if (imageUrl.isEmpty()) return;

        loadBitmap(imageUrl, new LoadCallback() {
            @Override
            public void onLoaded(Bitmap bitmap) {
                if (activity.isFinishing()) return;
                if (activity instanceof AppCompatActivity
                        && ((AppCompatActivity) activity).isDestroyed()) {
                    return;
                }
                activity.runOnUiThread(() -> showDialog(activity, bitmap));
            }

            @Override
            public void onError(String message) {
                if (activity.isFinishing()) return;
                activity.runOnUiThread(() -> new MaterialAlertDialogBuilder(activity,
                        com.google.android.material.R.style.ThemeOverlay_MaterialComponents_Dark)
                        .setTitle("Photo NASA")
                        .setMessage(message)
                        .setPositiveButton("OK", null)
                        .show());
            }
        });
    }

    private static void showDialog(Activity activity, Bitmap bitmap) {
        if (bitmap == null) {
            new MaterialAlertDialogBuilder(activity,
                    com.google.android.material.R.style.ThemeOverlay_MaterialComponents_Dark)
                    .setTitle("Photo NASA")
                    .setMessage("Impossible d'afficher la photo.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }

        ImageView imageView = new ImageView(activity);
        imageView.setAdjustViewBounds(true);
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        imageView.setImageBitmap(bitmap);
        imageView.setBackgroundColor(Color.parseColor("#FF1A1D24"));
        int maxW = (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.92f);
        imageView.setMaxWidth(maxW);
        imageView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(activity);
        scroll.setBackgroundColor(Color.parseColor("#FF0B0E14"));
        int pad = (int) (12 * activity.getResources().getDisplayMetrics().density);
        scroll.setPadding(pad, pad, pad, pad);
        scroll.addView(imageView);

        new MaterialAlertDialogBuilder(activity,
                com.google.android.material.R.style.ThemeOverlay_MaterialComponents_Dark)
                .setTitle("NASA — Image du jour")
                .setView(scroll)
                .setPositiveButton("Fermer", null)
                .show();
    }

    static File downloadToCache(Context app, String imageUrl) {
        HttpURLConnection conn = null;
        InputStream in = null;
        File out = new File(app.getCacheDir(), "nasa_apod_preview.jpg");
        try {
            URL url = new URL(imageUrl);
            conn = openImageConnection(url);
            int code = conn.getResponseCode();
            // Suivre une redirection manuelle (certains firmwares ne le font pas)
            if (code >= 300 && code < 400) {
                String loc = conn.getHeaderField("Location");
                conn.disconnect();
                if (loc == null || loc.isEmpty()) return null;
                if (loc.startsWith("http://")) loc = "https://" + loc.substring(7);
                conn = openImageConnection(new URL(loc));
                code = conn.getResponseCode();
            }
            if (code >= 400) {
                Log.w(TAG, "HTTP " + code + " for " + imageUrl);
                return null;
            }

            String contentType = conn.getContentType();
            if (contentType != null && contentType.contains("text/html")) {
                Log.w(TAG, "Got HTML instead of image for " + imageUrl);
                return null;
            }

            in = conn.getInputStream();
            try (FileOutputStream fos = new FileOutputStream(out)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
            }
            if (out.length() < 64) {
                Log.w(TAG, "Downloaded file too small: " + out.length());
                return null;
            }
            return out;
        } catch (Exception e) {
            Log.w(TAG, "Download failed: " + imageUrl, e);
            return null;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Exception ignored) {}
            if (conn != null) conn.disconnect();
        }
    }

    private static HttpURLConnection openImageConnection(URL url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(20_000);
        conn.setReadTimeout(45_000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 "
                        + "Chrome/120.0.0.0 Mobile Safari/537.36 Orbe-Android/1.0");
        conn.setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8");
        conn.connect();
        return conn;
    }

    public static void loadBitmap(String imageUrl, LoadCallback callback) {
        IO.execute(() -> {
            HttpURLConnection conn = null;
            InputStream in = null;
            try {
                conn = openImageConnection(new URL(imageUrl));
                int code = conn.getResponseCode();
                if (code >= 400) {
                    callback.onError("Photo NASA inaccessible (HTTP " + code + ").");
                    return;
                }

                in = conn.getInputStream();
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = 1;
                Bitmap bmp = BitmapFactory.decodeStream(in, null, opts);
                if (bmp == null) {
                    callback.onError("Format d'image non reconnu.");
                    return;
                }
                callback.onLoaded(bmp);
            } catch (Exception e) {
                callback.onError("Photo NASA : " + e.getMessage());
            } finally {
                try {
                    if (in != null) in.close();
                } catch (Exception ignored) {}
                if (conn != null) conn.disconnect();
            }
        });
    }
}
