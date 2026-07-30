package com.pegasuscorp.orbe;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import com.google.mlkit.vision.digitalink.Ink;

/**
 * Tracé plein écran — fond transparent, seul le trait est visible.
 */
public class InkDrawingView extends View {
    private static final long STROKE_VISIBLE_MS = 450;
    private static final float MIN_STROKE_DISTANCE_DP = 18f;

    private final Paint strokePaint = new Paint();
    private final Path path = new Path();
    private Ink.Builder inkBuilder = Ink.builder();
    private Ink.Stroke.Builder strokeBuilder;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean alwaysActive = true;
    private float strokeStartX;
    private float strokeStartY;
    private float minStrokeDistancePx;

    public interface DrawingCallback {
        void onDrawingFinished(Ink ink);
    }

    private DrawingCallback callback;

    public InkDrawingView(Context context, AttributeSet attrs) {
        super(context, attrs);
        minStrokeDistancePx = MIN_STROKE_DISTANCE_DP * getResources().getDisplayMetrics().density;
        setWillNotDraw(false);
        setBackgroundColor(Color.TRANSPARENT);

        strokePaint.setAntiAlias(true);
        strokePaint.setStrokeWidth(14f);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setColor(Color.parseColor("#B8FBF6"));
    }

    public void setAlwaysActive(boolean active) {
        alwaysActive = active;
        setVisibility(active ? VISIBLE : GONE);
        invalidate();
    }

    public void setCallback(DrawingCallback callback) {
        this.callback = callback;
    }

    public void setStrokeColor(int color) {
        strokePaint.setColor(color);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!alwaysActive || path.isEmpty()) return;
        canvas.drawPath(path, strokePaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!alwaysActive) return false;

        float x = event.getX();
        float y = event.getY();
        long t = System.currentTimeMillis();

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                handler.removeCallbacksAndMessages(null);
                strokeStartX = x;
                strokeStartY = y;
                path.moveTo(x, y);
                strokeBuilder = Ink.Stroke.builder();
                strokeBuilder.addPoint(Ink.Point.create(x, y, t));
                getParent().requestDisallowInterceptTouchEvent(true);
                break;
            case MotionEvent.ACTION_MOVE:
                path.lineTo(x, y);
                if (strokeBuilder != null) {
                    strokeBuilder.addPoint(Ink.Point.create(x, y, t));
                }
                break;
            case MotionEvent.ACTION_UP:
                path.lineTo(x, y);
                if (strokeBuilder != null) {
                    strokeBuilder.addPoint(Ink.Point.create(x, y, t));
                    float dx = x - strokeStartX;
                    float dy = y - strokeStartY;
                    if (dx * dx + dy * dy >= minStrokeDistancePx * minStrokeDistancePx) {
                        inkBuilder.addStroke(strokeBuilder.build());
                        Ink finished = inkBuilder.build();
                        if (callback != null) {
                            callback.onDrawingFinished(finished);
                        }
                        inkBuilder = Ink.builder();
                        handler.postDelayed(this::clear, STROKE_VISIBLE_MS);
                    } else {
                        clear();
                    }
                    strokeBuilder = null;
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                cancelStroke();
                break;
        }
        invalidate();
        return true;
    }

    /** Annule un trait en cours sans déclencher la reconnaissance. */
    public void cancelStroke() {
        handler.removeCallbacksAndMessages(null);
        clear();
    }

    public void clear() {
        path.reset();
        inkBuilder = Ink.builder();
        strokeBuilder = null;
        invalidate();
    }
}
