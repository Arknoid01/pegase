package com.pegasuscorp.orbe.copilot;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

/**
 * Complète le snapshot a11y via OCR quand l'arbre est vide (jeux, WebView).
 * Exécuté dans le process principal où MediaProjection est disponible.
 */
public final class OcrFallback {

    private static final String TAG = "OcrFallback";
    private static final int MIN_A11Y_NODES = 2;
    private static final long MIN_INTERVAL_MS = 5_000L;

    private static volatile long lastAttemptMs;

    private OcrFallback() {}

    public static boolean needsFallback(Context ctx) {
        return A11ySnapshot.loadNodes(ctx).size() < MIN_A11Y_NODES;
    }

    /**
     * Capture + OCR si l'arbre a11y est trop pauvre. Bloquant — appeler hors UI thread.
     */
    public static boolean tryEnrich(Context ctx, String packageName) {
        if (ctx == null || packageName == null) return false;
        if (!CopilotPrefs.isScreenAnalysisEnabled(ctx)) return false;
        if (!CopilotPrefs.isPackageAllowed(ctx, packageName)) return false;
        if (!needsFallback(ctx)) return false;

        long now = System.currentTimeMillis();
        if (now - lastAttemptMs < MIN_INTERVAL_MS) return false;
        lastAttemptMs = now;

        if (!ScreenCaptureHelper.hasPermission()) {
            Log.d(TAG, "OCR ignoré — pas de permission capture");
            return false;
        }

        Bitmap bitmap = ScreenCaptureHelper.captureBitmapBlocking(ctx);
        if (bitmap == null) return false;

        try {
            final String[] plainHolder = new String[1];
            final boolean[] done = {false};
            ScreenTextExtractor.recognizeBlocks(bitmap, new ScreenTextExtractor.BlocksCallback() {
                @Override
                public void onSuccess(java.util.List<ScreenTextExtractor.TextBlock> blocks) {
                    if (blocks != null && !blocks.isEmpty()) {
                        A11yTreeExtractor.mergeOcrBlocks(ctx, packageName, blocks);
                        plainHolder[0] = joinBlocks(blocks);
                    }
                    synchronized (done) {
                        done[0] = true;
                        done.notify();
                    }
                }

                @Override
                public void onError(String message) {
                    Log.w(TAG, "OCR: " + message);
                    synchronized (done) {
                        done[0] = true;
                        done.notify();
                    }
                }
            });
            synchronized (done) {
                if (!done[0]) {
                    try {
                        done.wait(4_000L);
                    } catch (InterruptedException ignored) {}
                }
            }
            if (plainHolder[0] != null && !plainHolder[0].isEmpty()) {
                ScreenContextStore.update(ctx, packageName, plainHolder[0]);
                return true;
            }
        } finally {
            bitmap.recycle();
        }
        return false;
    }

    private static String joinBlocks(java.util.List<ScreenTextExtractor.TextBlock> blocks) {
        StringBuilder sb = new StringBuilder();
        for (ScreenTextExtractor.TextBlock b : blocks) {
            if (b == null || b.text.isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(b.text);
        }
        return sb.toString().trim();
    }
}
