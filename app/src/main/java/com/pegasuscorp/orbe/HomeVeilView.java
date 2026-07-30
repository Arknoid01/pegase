package com.pegasuscorp.orbe;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * Voile semi-transparent au-dessus du fond Fluid / wallpaper.
 */
public class HomeVeilView extends View {

    private final Paint baseVeilPaint = new Paint();
    private final Paint radialVeilPaint = new Paint();
    private int accentColor = Color.parseColor("#220B7D8F");

    public HomeVeilView(Context context) {
        super(context);
        init();
    }

    public HomeVeilView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setWillNotDraw(false);
        baseVeilPaint.setColor(Color.parseColor("#5A0B0E14"));
        radialVeilPaint.setColor(Color.WHITE);
    }

    public void setAccentColor(int color) {
        accentColor = color;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawRect(0, 0, getWidth(), getHeight(), baseVeilPaint);

        float cx = getWidth() / 2f;
        float cy = getHeight() * 0.48f;
        float radius = Math.max(getWidth(), getHeight()) * 0.95f;
        int mid = blendAccent(accentColor, 0x440B0E14);
        radialVeilPaint.setShader(new RadialGradient(
                cx, cy, radius,
                new int[]{
                        Color.parseColor("#00000000"),
                        accentColor,
                        mid
                },
                new float[]{0f, 0.42f, 1f},
                Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, getWidth(), getHeight(), radialVeilPaint);
        radialVeilPaint.setShader(null);
    }

    private static int blendAccent(int accent, int fallback) {
        int aa = (accent >>> 24) & 0xFF;
        if (aa < 8) return fallback;
        return accent;
    }
}
