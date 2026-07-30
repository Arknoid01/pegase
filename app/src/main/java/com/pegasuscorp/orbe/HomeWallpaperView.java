package com.pegasuscorp.orbe;

import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.WindowManager;

import java.io.File;

/**
 * Affiche le fond d'écran (système ou personnalisé) avec flou optionnel.
 */
public class HomeWallpaperView extends View {

    private final Handler main = new Handler(Looper.getMainLooper());
    private Bitmap displayBitmap;
    private int blurRadius;
    private boolean loading;

    public HomeWallpaperView(Context context) {
        super(context);
        init();
    }

    public HomeWallpaperView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setWillNotDraw(false);
    }

    public void setBlurRadius(int radius) {
        int clamped = Math.max(0, Math.min(30, radius));
        if (blurRadius == clamped) return;
        blurRadius = clamped;
        updateVisibility();
        reloadAsync();
    }

    public void reload() {
        updateVisibility();
        if (getVisibility() == VISIBLE) reloadAsync();
    }

    /** Retour d'app : ne redécode le fond que s'il n'est pas déjà en mémoire. */
    public void reloadIfNeeded() {
        updateVisibility();
        if (getVisibility() != VISIBLE) return;
        if (displayBitmap != null && !displayBitmap.isRecycled()) return;
        reloadAsync();
    }

    /** Sous pression mémoire : libère le bitmap (rechargé au prochain affichage). */
    public void releaseBitmap() {
        if (displayBitmap != null) {
            displayBitmap.recycle();
            displayBitmap = null;
            invalidate();
        }
    }

    private void updateVisibility() {
        boolean show = blurRadius > 0 || PersonalizationStore.hasCustomWallpaper(getContext());
        setVisibility(show ? VISIBLE : GONE);
    }

    private void reloadAsync() {
        if (getVisibility() != VISIBLE || loading) return;
        loading = true;
        Context app = getContext().getApplicationContext();
        int[] screen = screenSize(app);
        // Demi-résolution max — le draw scale à l'écran (évite pic LMK au retour HOME)
        int viewW = getWidth() > 1 ? getWidth() : screen[0];
        int viewH = getHeight() > 1 ? getHeight() : screen[1];
        final int reqW = Math.max(1, Math.min(viewW, screen[0]) / 2);
        final int reqH = Math.max(1, Math.min(viewH, screen[1]) / 2);
        new Thread(() -> {
            Bitmap wallpaper = loadWallpaperBitmap(app, reqW, reqH);
            Bitmap blurred = wallpaper;
            if (wallpaper != null && blurRadius > 0) {
                blurred = BlurUtils.blur(wallpaper, blurRadius);
                if (blurred != wallpaper) wallpaper.recycle();
            }
            final Bitmap toShow = blurred;
            main.post(() -> {
                loading = false;
                if (displayBitmap != null) displayBitmap.recycle();
                displayBitmap = toShow;
                invalidate();
            });
        }, "HomeWallpaper").start();
    }

    private static int[] screenSize(Context context) {
        try {
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            DisplayMetrics dm = new DisplayMetrics();
            if (wm != null) {
                wm.getDefaultDisplay().getRealMetrics(dm);
                return new int[]{Math.max(1, dm.widthPixels), Math.max(1, dm.heightPixels)};
            }
        } catch (Exception ignored) {}
        return new int[]{1080, 1920};
    }

    private static Bitmap loadWallpaperBitmap(Context context, int width, int height) {
        if (PersonalizationStore.hasCustomWallpaper(context)) {
            String path = PersonalizationStore.getCustomWallpaperPath(context);
            File file = new File(path);
            if (file.exists()) {
                Bitmap decoded = decodeSampled(path, width, height);
                if (decoded != null) return decoded;
            }
        }
        try {
            WallpaperManager wm = WallpaperManager.getInstance(context);
            Drawable drawable = wm.getDrawable();
            if (drawable == null) return null;
            int w = width > 0 ? width : drawable.getIntrinsicWidth();
            int h = height > 0 ? height : drawable.getIntrinsicHeight();
            if (w <= 0) w = 1080;
            if (h <= 0) h = 1920;
            return BlurUtils.drawableToBitmap(drawable, w, h);
        } catch (Exception e) {
            return null;
        }
    }

    private static Bitmap decodeSampled(String path, int reqW, int reqH) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = calculateInSampleSize(bounds, reqW, reqH);
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeFile(path, opts);
    }

    private static int calculateInSampleSize(BitmapFactory.Options options, int reqW, int reqH) {
        int height = options.outHeight;
        int width = options.outWidth;
        int inSampleSize = 1;
        if (height > reqH || width > reqW) {
            int halfH = height / 2;
            int halfW = width / 2;
            while ((halfH / inSampleSize) >= reqH && (halfW / inSampleSize) >= reqW) {
                inSampleSize *= 2;
            }
        }
        return Math.max(1, inSampleSize);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        if (getVisibility() == VISIBLE && w > 0 && h > 0) reloadAsync();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (displayBitmap != null && !displayBitmap.isRecycled()) {
            canvas.drawBitmap(displayBitmap, null,
                    new Rect(0, 0, getWidth(), getHeight()), null);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        releaseBitmap();
        super.onDetachedFromWindow();
    }
}
