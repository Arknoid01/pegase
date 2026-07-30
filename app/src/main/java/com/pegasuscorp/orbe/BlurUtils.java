package com.pegasuscorp.orbe;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

/**
 * Flou bitmap léger (compatible API 24+).
 */
public final class BlurUtils {

    private BlurUtils() {}

    public static Bitmap blur(Bitmap source, int radius) {
        if (source == null || radius <= 0) return source;

        // Travailler en petit ; ne pas remonter en plein écran (pic RAM → LMK).
        int w = Math.max(1, source.getWidth() / 6);
        int h = Math.max(1, source.getHeight() / 6);
        Bitmap scaled = Bitmap.createScaledBitmap(source, w, h, true);
        Bitmap work = scaled.copy(Bitmap.Config.ARGB_8888, true);
        if (scaled != source && scaled != work && !scaled.isRecycled()) {
            scaled.recycle();
        }

        int iterations = Math.min(3, 1 + radius / 8);
        int r = Math.max(1, radius / 6);
        for (int i = 0; i < iterations; i++) {
            Bitmap next = boxBlur(work, r);
            if (next != work && work != null && !work.isRecycled()) {
                work.recycle();
            }
            work = next;
        }

        // Demi-résolution max — HomeWallpaperView scale à l'écran au draw
        int outW = Math.max(w, source.getWidth() / 2);
        int outH = Math.max(h, source.getHeight() / 2);
        if (outW <= work.getWidth() && outH <= work.getHeight()) {
            return work;
        }
        Bitmap out = Bitmap.createScaledBitmap(work, outW, outH, true);
        if (out != work && !work.isRecycled()) work.recycle();
        return out;
    }

    public static Bitmap drawableToBitmap(Drawable drawable, int width, int height) {
        if (drawable == null) return null;
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        if (drawable instanceof BitmapDrawable) {
            Bitmap bmp = ((BitmapDrawable) drawable).getBitmap();
            if (bmp != null) {
                canvas.drawBitmap(bmp, null, canvas.getClipBounds(), null);
                return bitmap;
            }
        }
        drawable.setBounds(0, 0, width, height);
        drawable.draw(canvas);
        return bitmap;
    }

    private static Bitmap boxBlur(Bitmap src, int radius) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] pixels = new int[w * h];
        src.getPixels(pixels, 0, w, 0, 0, w, h);
        int[] horizontal = new int[w * h];
        int[] output = new int[w * h];

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int r = 0, g = 0, b = 0, a = 0, count = 0;
                for (int k = -radius; k <= radius; k++) {
                    int px = Math.min(w - 1, Math.max(0, x + k));
                    int color = pixels[y * w + px];
                    a += (color >> 24) & 0xFF;
                    r += (color >> 16) & 0xFF;
                    g += (color >> 8) & 0xFF;
                    b += color & 0xFF;
                    count++;
                }
                horizontal[y * w + x] = ((a / count) << 24)
                        | ((r / count) << 16)
                        | ((g / count) << 8)
                        | (b / count);
            }
        }

        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                int r = 0, g = 0, b = 0, a = 0, count = 0;
                for (int k = -radius; k <= radius; k++) {
                    int py = Math.min(h - 1, Math.max(0, y + k));
                    int color = horizontal[py * w + x];
                    a += (color >> 24) & 0xFF;
                    r += (color >> 16) & 0xFF;
                    g += (color >> 8) & 0xFF;
                    b += color & 0xFF;
                    count++;
                }
                output[y * w + x] = ((a / count) << 24)
                        | ((r / count) << 16)
                        | ((g / count) << 8)
                        | (b / count);
            }
        }

        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(output, 0, w, 0, 0, w, h);
        return out;
    }
}
