package com.pegasuscorp.orbe;

import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Applique une capture du fond Fluid sur l'écran de verrouillage système.
 */
public final class FluidLockWallpaperSync {

    private static final long MIN_INTERVAL_MS = 45_000L;
    private static final int MAX_EXPORT_WIDTH = 1080;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static volatile long lastSyncMs;
    private static volatile String lastPhaseLabel = "";

    private FluidLockWallpaperSync() {}

    public static void syncNow(Context context) {
        Context app = context.getApplicationContext();
        if (!PersonalizationStore.isFluidEnabled(app)) return;
        if (!PersonalizationStore.isFluidLockWallpaperEnabled(app)) return;
        IO.execute(() -> applyInternal(app, false));
    }

    public static void syncIfDue(Context context) {
        Context app = context.getApplicationContext();
        if (!PersonalizationStore.isFluidEnabled(app)) return;
        if (!PersonalizationStore.isFluidLockWallpaperEnabled(app)) return;

        FluidPhase.State phase = FluidPhase.tinted(
                FluidPhase.current(), OrbThemes.get(PersonalizationStore.getColorIndex(app)));
        long now = System.currentTimeMillis();
        boolean phaseChanged = !phase.label.equals(lastPhaseLabel);
        if (!phaseChanged && now - lastSyncMs < MIN_INTERVAL_MS) return;
        IO.execute(() -> applyInternal(app, phaseChanged));
    }

    private static void applyInternal(Context app, boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastSyncMs < MIN_INTERVAL_MS) return;

        FluidPhase.State phase = FluidPhase.tinted(
                FluidPhase.current(), OrbThemes.get(PersonalizationStore.getColorIndex(app)));
        Point size = resolveExportSize(app);
        Bitmap bitmap = null;
        try {
            bitmap = FluidRenderer.renderBitmap(size.x, size.y, phase, 0f);
            WallpaperManager wm = WallpaperManager.getInstance(app);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                wm.setBitmap(bitmap, null, true, WallpaperManager.FLAG_LOCK);
            } else {
                wm.setBitmap(bitmap);
            }
            lastSyncMs = now;
            lastPhaseLabel = phase.label;
        } catch (Exception ignored) {
            // Permission refusée ou service indisponible — on ignore silencieusement.
        } finally {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        }
    }

    private static Point resolveExportSize(Context context) {
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        Point point = new Point();
        if (wm != null) {
            wm.getDefaultDisplay().getRealSize(point);
        }
        if (point.x <= 0 || point.y <= 0) {
            DisplayMetrics dm = context.getResources().getDisplayMetrics();
            point.x = dm.widthPixels;
            point.y = dm.heightPixels;
        }
        if (point.x > MAX_EXPORT_WIDTH) {
            float scale = MAX_EXPORT_WIDTH / (float) point.x;
            point.x = MAX_EXPORT_WIDTH;
            point.y = Math.max(1, Math.round(point.y * scale));
        }
        return point;
    }
}
