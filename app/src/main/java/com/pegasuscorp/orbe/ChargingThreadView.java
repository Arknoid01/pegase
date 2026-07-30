package com.pegasuscorp.orbe;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * Fils lumineux du bas de l'écran vers l'orbe pendant la charge.
 */
public class ChargingThreadView extends View {

    private static final float ORB_Y_FRACTION = 0.50f;
    private static final float ORB_RADIUS_FRACTION = 0.125f;

    private final Paint threadGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint threadCorePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint flowGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint flowCorePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint nodePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path threadPath = new Path();
    private final Path flowSegment = new Path();

    private boolean charging = false;
    private float flowPhase = 0f;
    private ValueAnimator flowAnimator;
    private int accentCore = Color.parseColor("#B8FBF6");
    private int accentMiddle = Color.parseColor("#35D0DD");

    public ChargingThreadView(Context context) {
        super(context);
        init();
    }

    public ChargingThreadView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
        setLayerType(LAYER_TYPE_SOFTWARE, null);

        threadGlowPaint.setStyle(Paint.Style.STROKE);
        threadGlowPaint.setStrokeWidth(10f);
        threadGlowPaint.setStrokeCap(Paint.Cap.ROUND);
        threadGlowPaint.setColor(Color.parseColor("#3035D0DD"));
        threadGlowPaint.setMaskFilter(new BlurMaskFilter(12f, BlurMaskFilter.Blur.NORMAL));

        threadCorePaint.setStyle(Paint.Style.STROKE);
        threadCorePaint.setStrokeWidth(2f);
        threadCorePaint.setStrokeCap(Paint.Cap.ROUND);
        threadCorePaint.setColor(Color.parseColor("#5535D0DD"));

        flowGlowPaint.setStyle(Paint.Style.STROKE);
        flowGlowPaint.setStrokeWidth(14f);
        flowGlowPaint.setStrokeCap(Paint.Cap.ROUND);
        flowGlowPaint.setColor(Color.parseColor("#9035D0DD"));
        flowGlowPaint.setMaskFilter(new BlurMaskFilter(16f, BlurMaskFilter.Blur.NORMAL));

        flowCorePaint.setStyle(Paint.Style.STROKE);
        flowCorePaint.setStrokeWidth(4.5f);
        flowCorePaint.setStrokeCap(Paint.Cap.ROUND);
        flowCorePaint.setColor(Color.parseColor("#E8B8FBF6"));

        nodePaint.setStyle(Paint.Style.FILL);
        nodePaint.setColor(Color.parseColor("#C8B8FBF6"));
    }

    public void setAccentColors(int core, int middle) {
        accentCore = core;
        accentMiddle = middle;
        threadGlowPaint.setColor(Color.argb(0x30, Color.red(middle), Color.green(middle), Color.blue(middle)));
        threadCorePaint.setColor(Color.argb(0x55, Color.red(middle), Color.green(middle), Color.blue(middle)));
        flowGlowPaint.setColor(Color.argb(0x90, Color.red(middle), Color.green(middle), Color.blue(middle)));
        flowCorePaint.setColor(Color.argb(0xE8, Color.red(core), Color.green(core), Color.blue(core)));
        nodePaint.setColor(Color.argb(0xC8, Color.red(core), Color.green(core), Color.blue(core)));
        invalidate();
    }

    public void setCharging(boolean active) {
        if (charging == active) return;
        charging = active;
        setVisibility(active ? VISIBLE : GONE);
        if (active) startFlowAnimation();
        else stopFlowAnimation();
        invalidate();
    }

    private void startFlowAnimation() {
        stopFlowAnimation();
        flowAnimator = ValueAnimator.ofFloat(0f, 1f);
        flowAnimator.setDuration(2200);
        flowAnimator.setRepeatCount(ValueAnimator.INFINITE);
        flowAnimator.setInterpolator(new LinearInterpolator());
        flowAnimator.addUpdateListener(a -> {
            flowPhase = (float) a.getAnimatedValue();
            invalidate();
        });
        flowAnimator.start();
    }

    private void stopFlowAnimation() {
        if (flowAnimator != null) {
            flowAnimator.cancel();
            flowAnimator = null;
        }
        flowPhase = 0f;
    }

    @Override
    protected void onDetachedFromWindow() {
        stopFlowAnimation();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!charging) return;

        float w = getWidth();
        float h = getHeight();
        float cx = w / 2f;
        float top = getPaddingTop();
        float bottom = h - getPaddingBottom();
        float cy = top + (bottom - top) * ORB_Y_FRACTION;
        float orbR = Math.min(w, h - top - getPaddingBottom()) * ORB_RADIUS_FRACTION;
        float endY = cy - orbR * 0.2f;

        threadPath.reset();
        threadPath.moveTo(cx, bottom);
        threadPath.cubicTo(
                cx + w * 0.06f, bottom - (bottom - endY) * 0.35f,
                cx - w * 0.04f, endY + (bottom - endY) * 0.35f,
                cx, endY);

        canvas.drawPath(threadPath, threadGlowPaint);
        canvas.drawPath(threadPath, threadCorePaint);

        PathMeasure measure = new PathMeasure(threadPath, false);
        float length = measure.getLength();
        float segLen = length * 0.28f;
        float start = length * flowPhase;
        float end = start + segLen;
        flowSegment.reset();
        if (end <= length) {
            measure.getSegment(start, end, flowSegment, true);
        } else {
            measure.getSegment(start, length, flowSegment, true);
            Path tail = new Path();
            measure.getSegment(0f, end - length, tail, true);
            flowSegment.addPath(tail);
        }

        canvas.drawPath(flowSegment, flowGlowPaint);
        flowCorePaint.setShader(new LinearGradient(
                cx, bottom, cx, endY,
                new int[]{
                        Color.argb(0x80, Color.red(accentCore), Color.green(accentCore), Color.blue(accentCore)),
                        Color.argb(0xE8, Color.red(accentCore), Color.green(accentCore), Color.blue(accentCore))
                },
                null, Shader.TileMode.CLAMP));
        canvas.drawPath(flowSegment, flowCorePaint);
        flowCorePaint.setShader(null);

        float nodePulse = 0.85f + 0.15f * (float) Math.sin(flowPhase * Math.PI * 2);
        canvas.drawCircle(cx, bottom, 5f * nodePulse, nodePaint);
        canvas.drawCircle(cx, endY, 4f * nodePulse, nodePaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return false;
    }
}
