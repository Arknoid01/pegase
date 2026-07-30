package com.pegasuscorp.orbe.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;

import com.pegasuscorp.orbe.memory.Entity;
import com.pegasuscorp.orbe.memory.MemoryGraphScene;

import java.util.HashMap;
import java.util.Map;

/**
 * Aperçu 3D futuriste du graphe mémoire — rotation auto + glisser pour tourner.
 */
public class MemoryGraph3DView extends View {

    private static final int COLOR_BG_TOP = Color.parseColor("#FF0A1628");
    private static final int COLOR_BG_BOTTOM = Color.parseColor("#FF050A12");
    private static final int COLOR_CYAN = Color.parseColor("#35D0DD");
    private static final int COLOR_PURPLE = Color.parseColor("#A855F7");
    private static final int COLOR_AMBER = Color.parseColor("#FBBF24");
    private static final int COLOR_DEVICE = Color.parseColor("#22D3EE");
    private static final int COLOR_GRID = Color.parseColor("#1835D0DD");

    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint nodePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint starPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private MemoryGraphScene.Scene scene = new MemoryGraphScene.Scene(
            new java.util.ArrayList<>(), new java.util.ArrayList<>());
    private final Map<String, MemoryGraphScene.Node> nodeIndex = new HashMap<>();

    private float rotY = 0.4f;
    private float rotX = 0.35f;
    private float userRotY;
    private float userRotX;
    private float lastTouchX;
    private float lastTouchY;
    private boolean dragging;
    private long startNanos;
    private boolean animating;

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!animating) return;
            if (startNanos == 0) startNanos = frameTimeNanos;
            if (!dragging) {
                float t = (frameTimeNanos - startNanos) / 1_000_000_000f;
                rotY = 0.4f + t * 0.35f;
                rotX = 0.35f + (float) Math.sin(t * 0.7f) * 0.12f;
            }
            invalidate();
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    public MemoryGraph3DView(Context context) {
        super(context);
        init();
    }

    public MemoryGraph3DView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setWillNotDraw(false);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        nodePaint.setStyle(Paint.Style.FILL);
        glowPaint.setStyle(Paint.Style.FILL);
        labelPaint.setColor(Color.parseColor("#CCFFFFFF"));
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setTextSize(sp(9));
        starPaint.setColor(Color.parseColor("#55FFFFFF"));
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setColor(COLOR_GRID);
        gridPaint.setStrokeWidth(dp(1));
    }

    public void setScene(MemoryGraphScene.Scene newScene) {
        scene = newScene != null ? newScene
                : new MemoryGraphScene.Scene(new java.util.ArrayList<>(), new java.util.ArrayList<>());
        nodeIndex.clear();
        for (MemoryGraphScene.Node node : scene.nodes) {
            nodeIndex.put(node.id, node);
        }
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startAnimation();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopAnimation();
        super.onDetachedFromWindow();
    }

    public void startAnimation() {
        if (animating) return;
        animating = true;
        startNanos = 0;
        Choreographer.getInstance().postFrameCallback(frameCallback);
    }

    public void stopAnimation() {
        animating = false;
        Choreographer.getInstance().removeFrameCallback(frameCallback);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = true;
                lastTouchX = event.getX();
                lastTouchY = event.getY();
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getX() - lastTouchX;
                float dy = event.getY() - lastTouchY;
                userRotY += dx * 0.01f;
                userRotX += dy * 0.008f;
                userRotX = clamp(userRotX, -1.2f, 1.2f);
                lastTouchX = event.getX();
                lastTouchY = event.getY();
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        drawBackground(canvas, w, h);
        drawStars(canvas, w, h);
        if (scene.isEmpty()) {
            drawEmpty(canvas, w, h);
            return;
        }

        float cx = w * 0.5f;
        float cy = h * 0.52f;
        float scale = Math.min(w, h) * 0.34f;
        float focal = 2.8f;
        float totalRotY = rotY + userRotY;
        float totalRotX = rotX + userRotX;

        drawGrid(canvas, cx, cy, scale, totalRotY, totalRotX, focal);
        drawEdges(canvas, cx, cy, scale, totalRotY, totalRotX, focal);
        drawNodes(canvas, cx, cy, scale, totalRotY, totalRotX, focal);
    }

    private void drawBackground(Canvas canvas, int w, int h) {
        Paint bg = new Paint();
        bg.setShader(new LinearGradient(0, 0, 0, h,
                COLOR_BG_TOP, COLOR_BG_BOTTOM, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, bg);
    }

    private void drawStars(Canvas canvas, int w, int h) {
        int count = 28;
        for (int i = 0; i < count; i++) {
            float x = (i * 73.7f) % w;
            float y = (i * 41.3f) % h;
            float r = 1f + (i % 3);
            starPaint.setAlpha(40 + (i * 17) % 100);
            canvas.drawCircle(x, y, r, starPaint);
        }
    }

    private void drawEmpty(Canvas canvas, int w, int h) {
        labelPaint.setTextSize(sp(12));
        labelPaint.setColor(Color.parseColor("#66FFFFFF"));
        canvas.drawText("Graphe vide — ajoute des souvenirs ou des entités",
                w * 0.5f, h * 0.5f, labelPaint);
    }

    private void drawGrid(Canvas canvas, float cx, float cy, float scale,
            float rotY, float rotX, float focal) {
        int lines = 8;
        float extent = 1.3f;
        for (int i = 0; i <= lines; i++) {
            float t = -extent + (2f * extent * i / lines);
            drawGridLine(canvas, cx, cy, scale, rotY, rotX, focal,
                    -extent, t, extent, t);
            drawGridLine(canvas, cx, cy, scale, rotY, rotX, focal,
                    t, -extent, t, extent);
        }
    }

    private void drawGridLine(Canvas canvas, float cx, float cy, float scale,
            float rotY, float rotX, float focal,
            float x1, float z1, float x2, float z2) {
        Projected a = project(x1, -0.9f, z1, cx, cy, scale, rotY, rotX, focal);
        Projected b = project(x2, -0.9f, z2, cx, cy, scale, rotY, rotX, focal);
        gridPaint.setAlpha((int) (40 + 80 * Math.min(a.depth, b.depth)));
        canvas.drawLine(a.x, a.y, b.x, b.y, gridPaint);
    }

    private void drawEdges(Canvas canvas, float cx, float cy, float scale,
            float rotY, float rotX, float focal) {
        for (MemoryGraphScene.Edge edge : scene.edges) {
            MemoryGraphScene.Node a = nodeIndex.get(edge.fromId);
            MemoryGraphScene.Node b = nodeIndex.get(edge.toId);
            if (a == null || b == null) continue;
            Projected pa = project(a, cx, cy, scale, rotY, rotX, focal);
            Projected pb = project(b, cx, cy, scale, rotY, rotX, focal);
            int alpha = edge.frozen ? 220 : (int) (80 + edge.weight * 140);
            int color = edge.entityLink ? COLOR_CYAN : COLOR_PURPLE;
            linePaint.setColor(color);
            linePaint.setAlpha(alpha);
            linePaint.setStrokeWidth(edge.entityLink ? dp(1.6f) : dp(1.1f));
            canvas.drawLine(pa.x, pa.y, pb.x, pb.y, linePaint);
            if (edge.frozen || edge.weight > 0.8) {
                linePaint.setAlpha(50);
                linePaint.setStrokeWidth(dp(4f));
                canvas.drawLine(pa.x, pa.y, pb.x, pb.y, linePaint);
            }
        }
    }

    private void drawNodes(Canvas canvas, float cx, float cy, float scale,
            float rotY, float rotX, float focal) {
        java.util.List<DrawNode> drawOrder = new java.util.ArrayList<>();
        for (MemoryGraphScene.Node node : scene.nodes) {
            Projected p = project(node, cx, cy, scale, rotY, rotX, focal);
            drawOrder.add(new DrawNode(node, p));
        }
        java.util.Collections.sort(drawOrder, (a, b) -> Float.compare(a.projected.depth, b.projected.depth));

        for (DrawNode item : drawOrder) {
            MemoryGraphScene.Node node = item.node;
            Projected p = item.projected;
            float radius = node.kind == MemoryGraphScene.NodeKind.ENTITY
                    ? dp(5f + node.vitality * 3f) * p.scale
                    : dp(3.5f + node.vitality * 2f) * p.scale;
            int core = nodeColor(node);
            glowPaint.setShader(new RadialGradient(
                    p.x, p.y, radius * 2.2f,
                    Color.argb(120, Color.red(core), Color.green(core), Color.blue(core)),
                    Color.TRANSPARENT, Shader.TileMode.CLAMP));
            canvas.drawCircle(p.x, p.y, radius * 2.2f, glowPaint);
            glowPaint.setShader(null);
            nodePaint.setColor(core);
            nodePaint.setAlpha(235);
            canvas.drawCircle(p.x, p.y, radius, nodePaint);
            if (p.scale > 0.55f && radius > dp(4f)) {
                labelPaint.setAlpha((int) (180 * Math.min(1f, p.scale)));
                canvas.drawText(node.label, p.x, p.y - radius - dp(3f), labelPaint);
            }
        }
    }

    private int nodeColor(MemoryGraphScene.Node node) {
        if (node.kind == MemoryGraphScene.NodeKind.MEMORY) return COLOR_AMBER;
        if (Entity.TYPE_PERSON.equals(node.entityType)) return COLOR_PURPLE;
        if (Entity.TYPE_DEVICE.equals(node.entityType)) return COLOR_DEVICE;
        if (Entity.TYPE_PROJECT.equals(node.entityType)) return COLOR_CYAN;
        return COLOR_CYAN;
    }

    private Projected project(MemoryGraphScene.Node node, float cx, float cy, float scale,
            float rotY, float rotX, float focal) {
        return project(node.x, node.y, node.z, cx, cy, scale, rotY, rotX, focal);
    }

    private Projected project(float x, float y, float z, float cx, float cy, float scale,
            float rotY, float rotX, float focal) {
        float cosY = (float) Math.cos(rotY);
        float sinY = (float) Math.sin(rotY);
        float xr = x * cosY - z * sinY;
        float zr = x * sinY + z * cosY;

        float cosX = (float) Math.cos(rotX);
        float sinX = (float) Math.sin(rotX);
        float yr = y * cosX - zr * sinX;
        float zf = y * sinX + zr * cosX;

        float depth = 1f / (focal + zf);
        float s = scale * depth;
        return new Projected(cx + xr * s, cy + yr * s, depth, s);
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private float sp(float v) {
        return v * getResources().getDisplayMetrics().scaledDensity;
    }

    private static final class Projected {
        final float x, y, depth, scale;

        Projected(float x, float y, float depth, float scale) {
            this.x = x;
            this.y = y;
            this.depth = depth;
            this.scale = scale;
        }
    }

    private static final class DrawNode {
        final MemoryGraphScene.Node node;
        final Projected projected;

        DrawNode(MemoryGraphScene.Node node, Projected projected) {
            this.node = node;
            this.projected = projected;
        }
    }
}
