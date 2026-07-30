package com.pegasuscorp.orbe.copilot;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import com.pegasuscorp.orbe.chat.OpenRouterVisionClient;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Capture d'écran via {@link MediaProjection}.
 * Sur Android 14+, la projection n'est créée que dans
 * {@link MediaProjectionCaptureService} après {@code startForeground(mediaProjection)}.
 */
public final class ScreenCaptureHelper {

    public interface Callback {
        void onNeedPermission();
        void onCaptured(byte[] jpeg);
        void onError(String message);
    }

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static int resultCode = Activity.RESULT_CANCELED;
    private static Intent resultData;

    private ScreenCaptureHelper() {}

    public static void storePermissionResult(int code, Intent data) {
        resultCode = code;
        resultData = data != null ? new Intent(data) : null;
    }

    public static boolean hasPermission() {
        return resultData != null && resultCode == Activity.RESULT_OK;
    }

    /** Arrête le FGS mediaProjection (invalide la projection active). */
    public static void releaseProjection(Context context) {
        if (context != null) {
            MediaProjectionCaptureService.stop(context);
        }
    }

    public static void clearPermission(Context context) {
        resultCode = Activity.RESULT_CANCELED;
        resultData = null;
        releaseProjection(context);
    }

    public static void capture(Context context, Callback callback) {
        Context app = context.getApplicationContext();
        IO.execute(() -> {
            try {
                if (!hasPermission()) {
                    MAIN.post(callback::onNeedPermission);
                    return;
                }
                MediaProjection projection = ensureProjection(app);
                if (projection == null) {
                    MAIN.post(callback::onNeedPermission);
                    return;
                }
                byte[] jpeg = captureJpegBlocking(app, projection);
                if (jpeg == null || jpeg.length == 0) {
                    MAIN.post(() -> callback.onError("Capture vide."));
                    return;
                }
                MAIN.post(() -> callback.onCaptured(jpeg));
            } catch (SecurityException e) {
                clearPermission(app);
                MAIN.post(callback::onNeedPermission);
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : "Erreur capture";
                MAIN.post(() -> callback.onError(msg));
            }
        });
    }

    /** Capture synchrone pour OCR (hors UI thread). */
    public static Bitmap captureBitmapBlocking(Context context) {
        Context app = context.getApplicationContext();
        try {
            if (!hasPermission()) return null;
            MediaProjection projection = ensureProjection(app);
            if (projection == null) return null;
            return captureBitmapInternal(app, projection);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Démarre le FGS typé mediaProjection si besoin, puis récupère la projection
     * créée <b>après</b> {@code startForeground}.
     */
    private static MediaProjection ensureProjection(Context app) {
        MediaProjection existing = MediaProjectionCaptureService.getProjection();
        if (existing != null) return existing;
        if (!hasPermission()) return null;
        return MediaProjectionCaptureService.ensureProjection(app, resultCode, resultData);
    }

    private static byte[] captureJpegBlocking(Context app, MediaProjection projection)
            throws Exception {
        Bitmap bitmap = captureBitmapInternal(app, projection);
        if (bitmap == null) return null;
        try {
            return OpenRouterVisionClient.compressBitmapToJpeg(bitmap);
        } finally {
            bitmap.recycle();
        }
    }

    private static Bitmap captureBitmapInternal(Context app, MediaProjection projection)
            throws Exception {
        WindowManager wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null || projection == null) return null;
        DisplayMetrics metrics = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(metrics);
        int w = metrics.widthPixels;
        int h = metrics.heightPixels;
        int density = metrics.densityDpi;

        ImageReader reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
        VirtualDisplay vd = projection.createVirtualDisplay(
                "pegase_screen_cap",
                w, h, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.getSurface(), null, null);

        Bitmap bitmap = null;
        try {
            Image image = null;
            for (int i = 0; i < 12 && image == null; i++) {
                Thread.sleep(40);
                image = reader.acquireLatestImage();
            }
            if (image == null) return null;
            bitmap = imageToBitmap(image, w, h);
            image.close();
        } finally {
            vd.release();
            reader.close();
        }
        return bitmap;
    }

    private static Bitmap imageToBitmap(Image image, int w, int h) {
        Image.Plane[] planes = image.getPlanes();
        if (planes == null || planes.length == 0) return null;
        ByteBuffer buffer = planes[0].getBuffer();
        int pixelStride = planes[0].getPixelStride();
        int rowStride = planes[0].getRowStride();
        int rowPadding = rowStride - pixelStride * w;
        Bitmap bmp = Bitmap.createBitmap(
                w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888);
        bmp.copyPixelsFromBuffer(buffer);
        if (bmp.getWidth() != w || bmp.getHeight() != h) {
            Bitmap cropped = Bitmap.createBitmap(bmp, 0, 0, w, h);
            bmp.recycle();
            return cropped;
        }
        return bmp;
    }
}
