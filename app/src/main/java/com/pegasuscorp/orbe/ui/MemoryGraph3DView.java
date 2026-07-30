package com.pegasuscorp.orbe.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;

import com.pegasuscorp.orbe.memory.Entity;
import com.pegasuscorp.orbe.memory.MemoryGraphScene;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Constellation mémoire — ciel immersif, synapses animées, focus au tap, inertie.
 */
public class MemoryGraph3DView extends View {

    public interface Listener {
        void onNodeFocused(MemoryGraphScene.Node node);
        void onFocusCleared();
    }

    private static final int BG_TOP = Color.parseColor("#FF061018");
    private static final int BG_MID = Color.parseColor("#FF0A1A28");
    private static final int BG_BOTTOM = Color.parseColor("#FF03060C");
    private static final int CYAN = Color.parseColor(OrbeTokens.CYAN);
    private static final int CYAN_CORE = Color.parseColor(OrbeTokens.CYAN_CORE);
    private static final int PERSON = Color.parseColor("#7DD3C7");
    private static final int AMBER = Color.parseColor("#E8B84A");
    private static final int AMBER_SOFT = Color.parseColor("#F5D78A");
    private static final int DEVICE = Color.parseColor("#5EC8E8");
    private static final int GRID = Color.parseColor("#1435D0DD");

    private static final float MIN_DEPTH_DENOM = 0.55f;
    private static final float MAX_NODE_SCALE = 2.2f;
    private static final float FOCAL = 2.85f;
    private static final float TAP_SLOP_DP = 12f;

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint nodePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint starPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint platePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint plateText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint plateMuted = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF plateRect = new RectF();

    private final float[] starX = new float[64];
    private final float[] starY = new float[64];
    private final float[] starR = new float[64];
    private final float[] starPhase = new float[64];

    private MemoryGraphScene.Scene scene = new MemoryGraphScene.Scene(
            new ArrayList<>(), new ArrayList<>());
    private final Map<String, MemoryGraphScene.Node> nodeIndex = new HashMap<>();
    private final Set<String> focusNeighborIds = new HashSet<>();

    private Listener listener;
    private String focusedId;

    private float autoRotY;
    private float autoRotX;
    private float userRotY;
    private float userRotX;
    private float velocityY;
    private float velocityX;
    private float zoom = 1f;

    private float lastTouchX;
    private float lastTouchY;
    private float downX;
    private float downY;
    private boolean dragging;
    private boolean moved;
    private long startNanos;
    private float timeSec;
    private boolean animating;
    private float entrance; // 0→1

    private final List<DrawNode> drawOrder = new ArrayList<>();
    private final Map<String, Projected> projectedCache = new HashMap<>();

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!animating) return;
            if (startNanos == 0) startNanos = frameTimeNanos;
            float t = (frameTimeNanos - startNanos) / 1_000_000_000f;
            float dt = Math.max(0.001f, t - timeSec);
            timeSec = t;

            if (entrance < 1f) {
                entrance = Math.min(1f, entrance + dt * 1.4f);
            }

            if (!dragging) {
                // Inertie
                userRotY += velocityY;
                userRotX += velocityX;
                userRotX = clamp(userRotX, -1.15f, 1.15f);
                velocityY *= 0.94f;
                velocityX *= 0.94f;
                if (Math.abs(velocityY) < 0.00015f) velocityY = 0f;
                if (Math.abs(velocityX) < 0.00015f) velocityX = 0f;

                // Orbite lente quand au repos
                if (velocityY == 0f && velocityX == 0f && focusedId == null) {
                    autoRotY = t * 0.18f;
                    autoRotX = 0.28f + (float) Math.sin(t * 0.45f) * 0.08f;
                } else if (focusedId == null) {
                    autoRotY += dt * 0.05f;
                }
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
        setClickable(true);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        nodePaint.setStyle(Paint.Style.FILL);
        glowPaint.setStyle(Paint.Style.FILL);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeCap(Paint.Cap.ROUND);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setColor(GRID);
        gridPaint.setStrokeWidth(dp(1));

        labelPaint.setColor(Color.parseColor("#E6FFFFFF"));
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setTypeface(OrbeTokens.typeLight());
        labelPaint.setTextSize(sp(10));

        platePaint.setStyle(Paint.Style.FILL);
        plateText.setColor(Color.WHITE);
        plateText.setTypeface(OrbeTokens.typeMedium());
        plateText.setTextSize(sp(13));
        plateMuted.setColor(Color.parseColor("#99FFFFFF"));
        plateMuted.setTypeface(OrbeTokens.typeLight());
        plateMuted.setTextSize(sp(11));

        // Champ d'étoiles déterministe
        for (int i = 0; i < starX.length; i++) {
            starX[i] = fract(i * 0.6180339f + 0.17f);
            starY[i] = fract(i * 0.381966f + 0.41f);
            starR[i] = 0.6f + (i % 5) * 0.35f;
            starPhase[i] = i * 0.37f;
        }
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setScene(MemoryGraphScene.Scene newScene) {
        scene = newScene != null ? newScene
                : new MemoryGraphScene.Scene(new ArrayList<>(), new ArrayList<>());
        nodeIndex.clear();
        for (MemoryGraphScene.Node node : scene.nodes) {
            nodeIndex.put(node.id, node);
        }
        focusedId = null;
        focusNeighborIds.clear();
        entrance = 0f;
        startNanos = 0;
        timeSec = 0f;
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
        Choreographer.getInstance().postFrameCallback(frameCallback);
    }

    public void stopAnimation() {
        animating = false;
        Choreographer.getInstance().removeFrameCallback(frameCallback);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float slop = dp(TAP_SLOP_DP);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = true;
                moved = false;
                velocityX = 0f;
                velocityY = 0f;
                lastTouchX = downX = event.getX();
                lastTouchY = downY = event.getY();
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = event.getX() - lastTouchX;
                float dy = event.getY() - lastTouchY;
                if (Math.hypot(event.getX() - downX, event.getY() - downY) > slop) {
                    moved = true;
                }
                userRotY += dx * 0.009f;
                userRotX += dy * 0.007f;
                userRotX = clamp(userRotX, -1.15f, 1.15f);
                velocityY = dx * 0.009f;
                velocityX = dy * 0.007f;
                lastTouchX = event.getX();
                lastTouchY = event.getY();
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                getParent().requestDisallowInterceptTouchEvent(false);
                if (!moved && event.getActionMasked() == MotionEvent.ACTION_UP) {
                    handleTap(event.getX(), event.getY());
                }
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private void handleTap(float x, float y) {
        if (scene.isEmpty()) return;
        DrawNode best = null;
        float bestDist = dp(36f);
        for (DrawNode item : drawOrder) {
            float r = hitRadius(item);
            float d = (float) Math.hypot(x - item.projected.x, y - item.projected.y);
            if (d < Math.max(bestDist, r * 1.8f) && (best == null || d < bestDist)) {
                best = item;
                bestDist = d;
            }
        }
        if (best == null) {
            clearNodeFocus();
            return;
        }
        if (best.node.id.equals(focusedId)) {
            clearNodeFocus();
        } else {
            setFocus(best.node.id);
        }
    }

    private void setFocus(String id) {
        focusedId = id;
        focusNeighborIds.clear();
        for (MemoryGraphScene.Edge edge : scene.edges) {
            if (id.equals(edge.fromId)) focusNeighborIds.add(edge.toId);
            else if (id.equals(edge.toId)) focusNeighborIds.add(edge.fromId);
        }
        MemoryGraphScene.Node node = nodeIndex.get(id);
        if (listener != null && node != null) listener.onNodeFocused(node);
        invalidate();
    }

    private void clearNodeFocus() {
        focusedId = null;
        focusNeighborIds.clear();
        if (listener != null) listener.onFocusCleared();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        float cx = w * 0.5f;
        float cy = h * 0.46f;
        float scale = Math.min(w, h) * 0.38f * zoom * (0.55f + 0.45f * easeOutCubic(entrance));
        float rotY = autoRotY + userRotY;
        float rotX = autoRotX + userRotX;

        drawBackground(canvas, w, h);
        drawNebula(canvas, w, h);
        drawStars(canvas, w, h);

        if (scene.isEmpty()) {
            drawEmpty(canvas, w, h);
            return;
        }

        rebuildProjections(cx, cy, scale, rotY, rotX);

        drawOrbitalRings(canvas, cx, cy, scale, rotY, rotX);
        drawGrid(canvas, cx, cy, scale, rotY, rotX);
        drawEdges(canvas);
        drawNodes(canvas);
        drawFocusPlate(canvas, w, h);
        drawCornerCaption(canvas, w, h);
    }

    private void rebuildProjections(float cx, float cy, float scale, float rotY, float rotX) {
        projectedCache.clear();
        drawOrder.clear();
        for (MemoryGraphScene.Node node : scene.nodes) {
            Projected p = project(node.x, node.y, node.z, cx, cy, scale, rotY, rotX);
            projectedCache.put(node.id, p);
            drawOrder.add(new DrawNode(node, p));
        }
        Collections.sort(drawOrder, (a, b) -> Float.compare(a.projected.depth, b.projected.depth));
    }

    private void drawBackground(Canvas canvas, int w, int h) {
        fillPaint.setShader(new LinearGradient(0, 0, 0, h,
                new int[]{BG_TOP, BG_MID, BG_BOTTOM},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, fillPaint);
        fillPaint.setShader(null);
    }

    private void drawNebula(Canvas canvas, int w, int h) {
        // Voile cyan discret — pas un glow flashy, une brume
        glowPaint.setShader(new RadialGradient(
                w * 0.35f, h * 0.4f, Math.max(w, h) * 0.55f,
                Color.argb(38, 53, 208, 221),
                Color.TRANSPARENT, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, glowPaint);

        glowPaint.setShader(new RadialGradient(
                w * 0.72f, h * 0.62f, Math.max(w, h) * 0.4f,
                Color.argb(28, 232, 184, 74),
                Color.TRANSPARENT, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, glowPaint);
        glowPaint.setShader(null);

        // Vignette
        glowPaint.setShader(new RadialGradient(
                w * 0.5f, h * 0.5f, Math.max(w, h) * 0.72f,
                Color.TRANSPARENT, Color.argb(160, 0, 0, 0), Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, glowPaint);
        glowPaint.setShader(null);
    }

    private void drawStars(Canvas canvas, int w, int h) {
        for (int i = 0; i < starX.length; i++) {
            float twinkle = 0.45f + 0.55f * (0.5f + 0.5f * (float) Math.sin(timeSec * (1.1f + (i % 7) * 0.13f) + starPhase[i]));
            int alpha = (int) ((28 + (i % 9) * 10) * twinkle);
            starPaint.setColor(Color.argb(alpha, 255, 255, 255));
            float x = starX[i] * w;
            float y = starY[i] * h;
            float r = dp(starR[i]);
            canvas.drawCircle(x, y, r, starPaint);
            if (i % 11 == 0) {
                starPaint.setAlpha(Math.min(255, alpha + 40));
                canvas.drawCircle(x, y, r * 0.35f, starPaint);
            }
        }
    }

    private void drawEmpty(Canvas canvas, int w, int h) {
        labelPaint.setTextSize(sp(13));
        labelPaint.setColor(Color.parseColor("#88FFFFFF"));
        labelPaint.setTypeface(OrbeTokens.typeLight());
        canvas.drawText("Le ciel est encore vide", w * 0.5f, h * 0.46f, labelPaint);
        plateMuted.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("Ajoute un souvenir — les étoiles apparaîtront",
                w * 0.5f, h * 0.46f + dp(18), plateMuted);
        plateMuted.setTextAlign(Paint.Align.LEFT);
    }

    private void drawOrbitalRings(Canvas canvas, float cx, float cy, float scale,
            float rotY, float rotX) {
        ringPaint.setColor(Color.argb(40, 53, 208, 221));
        ringPaint.setStrokeWidth(dp(1.1f));
        for (int ring = 0; ring < 3; ring++) {
            float radius = 0.55f + ring * 0.28f;
            Path path = new Path();
            boolean first = true;
            int steps = 48;
            for (int i = 0; i <= steps; i++) {
                float a = (float) (i * Math.PI * 2 / steps);
                float x = radius * (float) Math.cos(a);
                float z = radius * (float) Math.sin(a);
                Projected p = project(x, -0.05f * ring, z, cx, cy, scale, rotY, rotX);
                if (first) {
                    path.moveTo(p.x, p.y);
                    first = false;
                } else {
                    path.lineTo(p.x, p.y);
                }
            }
            path.close();
            ringPaint.setAlpha(28 + ring * 10);
            canvas.drawPath(path, ringPaint);
        }
    }

    private void drawGrid(Canvas canvas, float cx, float cy, float scale,
            float rotY, float rotX) {
        int lines = 7;
        float extent = 1.25f;
        for (int i = 0; i <= lines; i++) {
            float t = -extent + (2f * extent * i / lines);
            drawGridLine(canvas, cx, cy, scale, rotY, rotX, -extent, t, extent, t);
            drawGridLine(canvas, cx, cy, scale, rotY, rotX, t, -extent, t, extent);
        }
    }

    private void drawGridLine(Canvas canvas, float cx, float cy, float scale,
            float rotY, float rotX,
            float x1, float z1, float x2, float z2) {
        Projected a = project(x1, -0.95f, z1, cx, cy, scale, rotY, rotX);
        Projected b = project(x2, -0.95f, z2, cx, cy, scale, rotY, rotX);
        gridPaint.setAlpha((int) (22 + 55 * Math.min(a.depth, b.depth)));
        canvas.drawLine(a.x, a.y, b.x, b.y, gridPaint);
    }

    private void drawEdges(Canvas canvas) {
        for (MemoryGraphScene.Edge edge : scene.edges) {
            Projected pa = projectedCache.get(edge.fromId);
            Projected pb = projectedCache.get(edge.toId);
            if (pa == null || pb == null) continue;

            boolean hot = focusedId != null
                    && (focusedId.equals(edge.fromId) || focusedId.equals(edge.toId));
            boolean dim = focusedId != null && !hot;
            float dimFactor = dim ? 0.18f : 1f;

            int baseColor = edge.entityLink ? CYAN : AMBER;
            int alpha = (int) ((edge.frozen ? 200 : (70 + edge.weight * 130)) * dimFactor * entrance);
            linePaint.setColor(baseColor);
            linePaint.setAlpha(Math.max(0, Math.min(255, alpha)));
            linePaint.setStrokeWidth(dp(hot ? 2.2f : (edge.entityLink ? 1.5f : 1.1f)));
            canvas.drawLine(pa.x, pa.y, pb.x, pb.y, linePaint);

            if (hot || edge.frozen || edge.weight > 0.75) {
                linePaint.setAlpha((int) (36 * dimFactor));
                linePaint.setStrokeWidth(dp(hot ? 6f : 3.5f));
                canvas.drawLine(pa.x, pa.y, pb.x, pb.y, linePaint);
            }

            // Synapse — pulse qui voyage sur le lien
            if (!dim && entrance > 0.6f) {
                float phase = fract(timeSec * (0.35f + (float) edge.weight * 0.25f)
                        + edge.fromId.hashCode() * 0.001f);
                float px = pa.x + (pb.x - pa.x) * phase;
                float py = pa.y + (pb.y - pa.y) * phase;
                int pulseAlpha = (int) (160 * dimFactor * (0.4f + 0.6f * (float) Math.sin(phase * Math.PI)));
                glowPaint.setShader(new RadialGradient(
                        px, py, dp(hot ? 7f : 4.5f),
                        Color.argb(pulseAlpha, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor)),
                        Color.TRANSPARENT, Shader.TileMode.CLAMP));
                canvas.drawCircle(px, py, dp(hot ? 7f : 4.5f), glowPaint);
                glowPaint.setShader(null);
            }
        }
    }

    private void drawNodes(Canvas canvas) {
        for (DrawNode item : drawOrder) {
            MemoryGraphScene.Node node = item.node;
            Projected p = item.projected;
            boolean focused = node.id.equals(focusedId);
            boolean neighbor = focusNeighborIds.contains(node.id);
            boolean dim = focusedId != null && !focused && !neighbor;

            float nodeScale = Math.min(p.scale, MAX_NODE_SCALE);
            float radius = node.kind == MemoryGraphScene.NodeKind.ENTITY
                    ? dp(5.5f + (float) node.vitality * 3.5f) * nodeScale
                    : dp(4f + (float) node.vitality * 2.2f) * nodeScale;
            radius = Math.min(radius, Math.min(getWidth(), getHeight()) * 0.07f);
            if (focused) radius *= 1.35f;
            radius *= 0.4f + 0.6f * easeOutCubic(entrance);

            int core = nodeColor(node);
            float alphaMul = dim ? 0.22f : 1f;

            float glowR = Math.max(radius * (focused ? 3.2f : 2.4f), 1f);
            int glowA = (int) ((focused ? 150 : 95) * alphaMul);
            glowPaint.setShader(new RadialGradient(
                    p.x, p.y, glowR,
                    Color.argb(glowA, Color.red(core), Color.green(core), Color.blue(core)),
                    Color.TRANSPARENT, Shader.TileMode.CLAMP));
            canvas.drawCircle(p.x, p.y, glowR, glowPaint);
            glowPaint.setShader(null);

            // Anneau de focus qui respire
            if (focused) {
                float breathe = 0.85f + 0.15f * (0.5f + 0.5f * (float) Math.sin(timeSec * 2.4f));
                ringPaint.setColor(CYAN_CORE);
                ringPaint.setAlpha(160);
                ringPaint.setStrokeWidth(dp(1.4f));
                canvas.drawCircle(p.x, p.y, radius * 1.55f * breathe, ringPaint);
            }

            // Core
            nodePaint.setColor(core);
            nodePaint.setAlpha((int) (240 * alphaMul));
            canvas.drawCircle(p.x, p.y, radius, nodePaint);

            // Highlight specular
            nodePaint.setColor(Color.WHITE);
            nodePaint.setAlpha((int) (70 * alphaMul));
            canvas.drawCircle(p.x - radius * 0.28f, p.y - radius * 0.28f, radius * 0.28f, nodePaint);

            boolean showLabel = focused || neighbor
                    || (focusedId == null
                        && node.kind == MemoryGraphScene.NodeKind.ENTITY
                        && nodeScale > 0.7f
                        && radius > dp(5f));
            if (showLabel) {
                String label = focused ? node.label : clipLabel(node.label, focused ? 28 : 14);
                labelPaint.setTextSize(sp(focused ? 12f : 9.5f));
                labelPaint.setAlpha((int) ((focused ? 240 : 175) * alphaMul));
                labelPaint.setTypeface(focused ? OrbeTokens.typeMedium() : OrbeTokens.typeLight());
                // Ombre légère pour lisibilité
                labelPaint.setColor(Color.argb((int) (120 * alphaMul), 0, 0, 0));
                canvas.drawText(label, p.x + dp(0.5f), p.y - radius - dp(5f) + dp(0.5f), labelPaint);
                labelPaint.setColor(Color.argb((int) ((focused ? 245 : 210) * alphaMul), 255, 255, 255));
                canvas.drawText(label, p.x, p.y - radius - dp(5f), labelPaint);
            }
        }
    }

    private void drawFocusPlate(Canvas canvas, int w, int h) {
        if (focusedId == null) return;
        MemoryGraphScene.Node node = nodeIndex.get(focusedId);
        if (node == null) return;

        float pad = dp(12);
        float plateH = dp(56);
        plateRect.set(pad, h - plateH - pad, w - pad, h - pad);

        platePaint.setColor(Color.argb(200, 10, 18, 26));
        canvas.drawRoundRect(plateRect, dp(12), dp(12), platePaint);
        platePaint.setStyle(Paint.Style.STROKE);
        platePaint.setStrokeWidth(dp(1));
        platePaint.setColor(Color.argb(120, 53, 208, 221));
        canvas.drawRoundRect(plateRect, dp(12), dp(12), platePaint);
        platePaint.setStyle(Paint.Style.FILL);

        // Pastille couleur
        float dotX = plateRect.left + dp(18);
        float dotY = plateRect.centerY();
        nodePaint.setColor(nodeColor(node));
        nodePaint.setAlpha(255);
        canvas.drawCircle(dotX, dotY, dp(6), nodePaint);

        String kind = node.kind == MemoryGraphScene.NodeKind.ENTITY
                ? kindLabel(node.entityType)
                : "Souvenir";
        String meta = String.format(Locale.FRANCE, "%s · vitalité %.0f%% · %d lien%s",
                kind,
                node.vitality * 100.0,
                focusNeighborIds.size(),
                focusNeighborIds.size() > 1 ? "s" : "");

        plateText.setTextAlign(Paint.Align.LEFT);
        plateMuted.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(clipLabel(node.label, 28), plateRect.left + dp(34), plateRect.top + dp(24), plateText);
        canvas.drawText(meta, plateRect.left + dp(34), plateRect.top + dp(42), plateMuted);
    }

    private void drawCornerCaption(Canvas canvas, int w, int h) {
        if (focusedId != null) return;
        plateMuted.setTextAlign(Paint.Align.LEFT);
        plateMuted.setTextSize(sp(10));
        plateMuted.setAlpha(140);
        canvas.drawText("Touche une étoile · glisse le ciel",
                dp(12), h - dp(14), plateMuted);
        plateMuted.setAlpha(255);
        plateMuted.setTextSize(sp(11));
    }

    private float hitRadius(DrawNode item) {
        float nodeScale = Math.min(item.projected.scale, MAX_NODE_SCALE);
        float radius = item.node.kind == MemoryGraphScene.NodeKind.ENTITY
                ? dp(5.5f + (float) item.node.vitality * 3.5f) * nodeScale
                : dp(4f + (float) item.node.vitality * 2.2f) * nodeScale;
        return Math.min(radius, Math.min(getWidth(), getHeight()) * 0.07f);
    }

    private int nodeColor(MemoryGraphScene.Node node) {
        if (node.kind == MemoryGraphScene.NodeKind.MEMORY) return AMBER;
        if (Entity.TYPE_PERSON.equals(node.entityType)) return PERSON;
        if (Entity.TYPE_DEVICE.equals(node.entityType)) return DEVICE;
        if (Entity.TYPE_PROJECT.equals(node.entityType)) return CYAN;
        return CYAN;
    }

    private static String kindLabel(String entityType) {
        if (Entity.TYPE_PERSON.equals(entityType)) return "Personne";
        if (Entity.TYPE_DEVICE.equals(entityType)) return "Appareil";
        if (Entity.TYPE_PROJECT.equals(entityType)) return "Projet";
        if (entityType == null || entityType.isEmpty()) return "Entité";
        return entityType.substring(0, 1).toUpperCase(Locale.ROOT) + entityType.substring(1);
    }

    private Projected project(float x, float y, float z, float cx, float cy, float scale,
            float rotY, float rotX) {
        float cosY = (float) Math.cos(rotY);
        float sinY = (float) Math.sin(rotY);
        float xr = x * cosY - z * sinY;
        float zr = x * sinY + z * cosY;

        float cosX = (float) Math.cos(rotX);
        float sinX = (float) Math.sin(rotX);
        float yr = y * cosX - zr * sinX;
        float zf = y * sinX + zr * cosX;

        float depth = 1f / Math.max(MIN_DEPTH_DENOM, FOCAL + zf);
        float s = Math.min(scale * depth, scale * (1f / MIN_DEPTH_DENOM));
        return new Projected(cx + xr * s, cy + yr * s, depth, s);
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private static float fract(float v) {
        return v - (float) Math.floor(v);
    }

    private static float easeOutCubic(float t) {
        float u = 1f - t;
        return 1f - u * u * u;
    }

    private static String clipLabel(String text, int max) {
        if (text == null) return "";
        String t = text.trim().replace('\n', ' ');
        if (t.length() <= max) return t;
        return t.substring(0, Math.max(1, max - 1)) + "…";
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
