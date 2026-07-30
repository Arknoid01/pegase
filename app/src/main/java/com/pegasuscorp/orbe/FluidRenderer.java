package com.pegasuscorp.orbe;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;

/**
 * Rendu partagé du fond Fluid (vue accueil + export fond écran verrouillage).
 */
public final class FluidRenderer {

    private static final Paint BASE_PAINT = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint BLOB_PAINT = new Paint(Paint.ANTI_ALIAS_FLAG);

    private FluidRenderer() {}

    public static void draw(Canvas canvas, int width, int height,
                            FluidPhase.State phase, float drift) {
        if (width <= 0 || height <= 0 || phase == null) return;

        BASE_PAINT.setShader(new LinearGradient(
                0, 0, 0, height,
                phase.topColor, phase.bottomColor,
                Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, width, height, BASE_PAINT);
        BASE_PAINT.setShader(null);

        float cx = width * 0.5f;
        float cy = height * 0.46f;
        float maxR = Math.max(width, height) * 0.85f;

        drawBlob(canvas, cx + width * 0.18f * sin(drift),
                cy + height * 0.10f * cos(drift * 0.9f),
                maxR * 0.55f, phase.blobA);
        drawBlob(canvas, cx + width * 0.22f * cos(drift * 1.1f),
                cy + height * 0.20f * sin(drift * 0.8f),
                maxR * 0.48f, phase.blobB);
        drawBlob(canvas, cx + width * 0.12f * sin(drift * 0.7f + 1.2f),
                cy + height * 0.28f * cos(drift * 1.05f),
                maxR * 0.62f, phase.blobC);
    }

    public static Bitmap renderBitmap(int width, int height,
                                      FluidPhase.State phase, float drift) {
        int w = Math.max(1, width);
        int h = Math.max(1, height);
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        draw(new Canvas(bitmap), w, h, phase, drift);
        return bitmap;
    }

    private static void drawBlob(Canvas canvas, float cx, float cy, float radius, int color) {
        BLOB_PAINT.setShader(new RadialGradient(
                cx, cy, radius,
                new int[]{color, color & 0x00FFFFFF},
                new float[]{0f, 1f},
                Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, radius, BLOB_PAINT);
        BLOB_PAINT.setShader(null);
    }

    private static float sin(float v) {
        return (float) Math.sin(v);
    }

    private static float cos(float v) {
        return (float) Math.cos(v);
    }
}
