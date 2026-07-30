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
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Capture d'écran via MediaProjection (consentement une fois par session). */
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
    private static MediaProjection projection;

    private ScreenCaptureHelper() {}

    public static void storePermissionResult(int code, Intent data) {
        releaseProjection();
        resultCode = code;
        resultData = data;
    }

    public static boolean hasPermission() {
        return resultData != null && resultCode == Activity.RESULT_OK;
    }

    public static void releaseProjection() {
        if (projection != null) {
            try {
                projection.stop();
            } catch (Exception ignored) {}
            projection = null;
        }
    }

    public static void capture(Context context, Callback callback) {
        Context app = context.getApplicationContext();
        IO.execute(() -> {
            try {
                if (!hasPermission()) {
                    MAIN.post(callback::onNeedPermission);
                    return;
                }
                ensureProjection(app);
                if (projection == null) {
                    MAIN.post(callback::onNeedPermission);
                    return;
                }
                byte[] jpeg = captureJpegBlocking(app);
                if (jpeg == null || jpeg.length == 0) {
                    MAIN.post(() -> callback.onError("Capture vide."));
                    return;
                }
                MAIN.post(() -> callback.onCaptured(jpeg));
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
            ensureProjection(app);
            if (projection == null) return null;
            return captureBitmapInternal(app);
        } catch (Exception e) {
            return null;
        }
    }

    private static void ensureProjection(Context app) {
        if (projection != null) return;
        MediaProjectionManager mpm = (MediaProjectionManager)
                app.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mpm == null || resultData == null) return;
        projection = mpm.getMediaProjection(resultCode, resultData);
        if (projection != null) {
            projection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    projection = null;
                }
            }, MAIN);
        }
    }

    private static byte[] captureJpegBlocking(Context app) throws Exception {
        Bitmap bitmap = captureBitmapInternal(app);
        if (bitmap == null) return null;
        try {
            return OpenRouterVisionClient.compressBitmapToJpeg(bitmap);
        } finally {
            bitmap.recycle();
        }
    }

    private static Bitmap captureBitmapInternal(Context app) throws Exception {
        WindowManager wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) return null;
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
