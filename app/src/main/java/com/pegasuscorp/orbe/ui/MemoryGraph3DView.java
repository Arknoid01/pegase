package com.pegasuscorp.orbe.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
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
 * Constellation mémoire — ciel immersif, focus au tap, inertie.
 * Rendu volontairement léger : pas de shaders par nœud/arête à chaque frame.
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
    private static final int DEVICE = Color.parseColor("#5EC8E8");

    private static final float MIN_DEPTH_DENOM = 0.55f;
    private static final float MAX_NODE_SCALE = 2.2f;
    private static final float FOCAL = 2.85f;
    private static final float TAP_SLOP_DP = 12f;
    private static final long IDLE_FRAME_NS = 42_000_000L;   // ~24 fps au repos
    private static final long ACTIVE_FRAME_NS = 16_000_000L; // ~60 fps drag / entrée

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint nodePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint starPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint platePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint plateText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint plateMuted = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF plateRect = new RectF();

    private static final int STAR_COUNT = 28;
    private final float[] starX = new float[STAR_COUNT];
    private final float[] starY = new float[STAR_COUNT];
    private final float[] starR = new float[STAR_COUNT];
    private final float[] starPhase = new float[STAR_COUNT];

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
    private long lastFrameNanos;

    private Bitmap skyBitmap;
    private int skyW;
    private int skyH;

    private float cosY = 1f;
    private float sinY = 0f;
    private float cosX = 1f;
    private float sinX = 0f;

    private final List<DrawNode> drawOrder = new ArrayList<>();
    private final Map<String, Projected> projectedCache = new HashMap<>();

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!animating) return;

            boolean busy = dragging
                    || entrance < 1f
                    || Math.abs(velocityY) > 0.00015f
                    || Math.abs(velocityX) > 0.00015f;
            long minInterval = busy ? ACTIVE_FRAME_NS : IDLE_FRAME_NS;
            if (lastFrameNanos != 0 && frameTimeNanos - lastFrameNanos < minInterval) {
                Choreographer.getInstance().postFrameCallback(this);
                return;
            }
            lastFrameNanos = frameTimeNanos;

            if (startNanos == 0) startNanos = frameTimeNanos;
            float t = (frameTimeNanos - startNanos) / 1_000_000_000f;
            float dt = Math.max(0.001f, t - timeSec);
            timeSec = t;

            if (entrance < 1f) {
                entrance = Math.min(1f, entrance + dt * 1.4f);
            }

            if (!dragging) {
                userRotY += velocityY;
                userRotX += velocityX;
                userRotX = clamp(userRotX, -1.15f, 1.15f);
                velocityY *= 0.94f;
                velocityX *= 0.94f;
                if (Math.abs(velocityY) < 0.00015f) velocityY = 0f;
                if (Math.abs(velocityX) < 0.00015f) velocityX = 0f;

                if (velocityY == 0f && velocityX == 0f && focusedId == null) {
                    autoRotY = t * 0.12f;
                    autoRotX = 0.22f + (float) Math.sin(t * 0.35f) * 0.05f;
                } else if (focusedId == null) {
                    autoRotY += dt * 0.04f;
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
        setLayerType(LAYER_TYPE_HARDWARE, null);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        nodePaint.setStyle(Paint.Style.FILL);
        glowPaint.setStyle(Paint.Style.FILL);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeCap(Paint.Cap.ROUND);

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

        for (int i = 0; i < STAR_COUNT; i++) {
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
        float maxR = 1.6f;
        for (MemoryGraphScene.Node node : scene.nodes) {
            nodeIndex.put(node.id, node);
            float r = (float) Math.sqrt(node.x * node.x + node.y * node.y + node.z * node.z);
            if (r > maxR) maxR = r;
        }
        zoom = clamp(2.15f / maxR, 0.55f, 1.15f);
        focusedId = null;
        focusNeighborIds.clear();
        entrance = 0f;
        startNanos = 0;
        timeSec = 0f;
        lastFrameNanos = 0;
        ensureDrawPool();
        invalidate();
    }

    private void ensureDrawPool() {
        while (drawOrder.size() < scene.nodes.size()) {
            drawOrder.add(new DrawNode());
        }
        projectedCache.clear();
        for (int i = 0; i < scene.nodes.size(); i++) {
            DrawNode dn = drawOrder.get(i);
            dn.node = scene.nodes.get(i);
            projectedCache.put(dn.node.id, dn.projected);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startAnimation();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopAnimation();
        if (skyBitmap != null) {
            skyBitmap.recycle();
            skyBitmap = null;
            skyW = skyH = 0;
        }
        super.onDetachedFromWindow();
    }

    public void startAnimation() {
        if (animating) return;
        animating = true;
        lastFrameNanos = 0;
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
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
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
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(false);
                }
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
        int n = Math.min(drawOrder.size(), scene.nodes.size());
        for (int i = 0; i < n; i++) {
            DrawNode item = drawOrder.get(i);
            if (item.node == null) continue;
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
        float scale = Math.min(w, h) * 0.42f * zoom * (0.55f + 0.45f * easeOutCubic(entrance));
        float rotY = autoRotY + userRotY;
        float rotX = autoRotX + userRotX;
        cosY = (float) Math.cos(rotY);
        sinY = (float) Math.sin(rotY);
        cosX = (float) Math.cos(rotX);
        sinX = (float) Math.sin(rotX);

        drawSkyCached(canvas, w, h);
        drawStars(canvas, w, h);

        if (scene.isEmpty()) {
            drawEmpty(canvas, w, h);
            return;
        }

        rebuildProjections(cx, cy, scale);
        drawEdges(canvas);
        drawNodes(canvas);
        drawFocusPlate(canvas, w, h);
        drawCornerCaption(canvas, w, h);
    }

    private void drawSkyCached(Canvas canvas, int w, int h) {
        if (skyBitmap == null || skyW != w || skyH != h) {
            if (skyBitmap != null) skyBitmap.recycle();
            skyBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            skyW = w;
            skyH = h;
            Canvas sky = new Canvas(skyBitmap);
            fillPaint.setShader(new LinearGradient(0, 0, 0, h,
                    new int[]{BG_TOP, BG_MID, BG_BOTTOM},
                    new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
            sky.drawRect(0, 0, w, h, fillPaint);
            fillPaint.setShader(null);

            glowPaint.setShader(new RadialGradient(
                    w * 0.35f, h * 0.4f, Math.max(w, h) * 0.55f,
                    Color.argb(38, 53, 208, 221),
                    Color.TRANSPARENT, Shader.TileMode.CLAMP));
            sky.drawRect(0, 0, w, h, glowPaint);
            glowPaint.setShader(new RadialGradient(
                    w * 0.72f, h * 0.62f, Math.max(w, h) * 0.4f,
                    Color.argb(28, 232, 184, 74),
                    Color.TRANSPARENT, Shader.TileMode.CLAMP));
            sky.drawRect(0, 0, w, h, glowPaint);
            glowPaint.setShader(new RadialGradient(
                    w * 0.5f, h * 0.5f, Math.max(w, h) * 0.72f,
                    Color.TRANSPARENT, Color.argb(160, 0, 0, 0), Shader.TileMode.CLAMP));
            sky.drawRect(0, 0, w, h, glowPaint);
            glowPaint.setShader(null);
        }
        canvas.drawBitmap(skyBitmap, 0, 0, null);
    }

    private void rebuildProjections(float cx, float cy, float scale) {
        int n = scene.nodes.size();
        ensureDrawPool();
        for (int i = 0; i < n; i++) {
            DrawNode item = drawOrder.get(i);
            MemoryGraphScene.Node node = item.node;
            projectInto(node.x, node.y, node.z, cx, cy, scale, item.projected);
        }
        Collections.sort(drawOrder.subList(0, n),
                (a, b) -> Float.compare(a.projected.depth, b.projected.depth));
    }

    private void drawStars(Canvas canvas, int w, int h) {
        for (int i = 0; i < STAR_COUNT; i++) {
            float twinkle = 0.55f + 0.45f * (0.5f + 0.5f
                    * (float) Math.sin(timeSec * (0.7f + (i % 5) * 0.11f) + starPhase[i]));
            int alpha = (int) ((24 + (i % 7) * 8) * twinkle);
            starPaint.setColor(Color.argb(alpha, 255, 255, 255));
            canvas.drawCircle(starX[i] * w, starY[i] * h, dp(starR[i]), starPaint);
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

    private void drawEdges(Canvas canvas) {
        // Au repos : squelette entités seulement. Au focus : liens de l'étoile.
        for (MemoryGraphScene.Edge edge : scene.edges) {
            boolean hot = focusedId != null
                    && (focusedId.equals(edge.fromId) || focusedId.equals(edge.toId));
            if (focusedId == null) {
                if (!edge.entityLink) continue;
            } else if (!hot) {
                continue;
            }

            Projected pa = projectedCache.get(edge.fromId);
            Projected pb = projectedCache.get(edge.toId);
            if (pa == null || pb == null) continue;

            int baseColor = edge.entityLink ? CYAN : AMBER;
            int alpha = (int) ((edge.frozen ? 170 : (55 + edge.weight * 100)) * entrance);
            if (hot) alpha = Math.min(255, alpha + 60);
            linePaint.setColor(baseColor);
            linePaint.setAlpha(Math.max(0, Math.min(255, alpha)));
            linePaint.setStrokeWidth(dp(hot ? 2f : (edge.entityLink ? 1.3f : 1.1f)));
            canvas.drawLine(pa.x, pa.y, pb.x, pb.y, linePaint);
        }
    }

    private void drawNodes(Canvas canvas) {
        int n = Math.min(drawOrder.size(), scene.nodes.size());
        int entityLabelsLeft = focusedId == null ? 6 : 0;

        for (int i = 0; i < n; i++) {
            DrawNode item = drawOrder.get(i);
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
            float alphaMul = dim ? 0.2f : 1f;

            // Halo soft sans RadialGradient (coûteux) — cercle translucide.
            if (focused || (!dim && node.kind == MemoryGraphScene.NodeKind.ENTITY)) {
                glowPaint.setColor(Color.argb(
                        (int) ((focused ? 90 : 45) * alphaMul),
                        Color.red(core), Color.green(core), Color.blue(core)));
                canvas.drawCircle(p.x, p.y, radius * (focused ? 2.6f : 2.0f), glowPaint);
            }

            if (focused) {
                float breathe = 0.88f + 0.12f * (0.5f + 0.5f * (float) Math.sin(timeSec * 2.2f));
                ringPaint.setColor(CYAN_CORE);
                ringPaint.setAlpha(150);
                ringPaint.setStrokeWidth(dp(1.3f));
                canvas.drawCircle(p.x, p.y, radius * 1.5f * breathe, ringPaint);
            }

            nodePaint.setColor(core);
            nodePaint.setAlpha((int) (235 * alphaMul));
            canvas.drawCircle(p.x, p.y, radius, nodePaint);

            if (focused) {
                nodePaint.setColor(Color.WHITE);
                nodePaint.setAlpha((int) (70 * alphaMul));
                canvas.drawCircle(p.x - radius * 0.28f, p.y - radius * 0.28f,
                        radius * 0.28f, nodePaint);
            }

            boolean showLabel = focused || neighbor;
            if (!showLabel && focusedId == null
                    && node.kind == MemoryGraphScene.NodeKind.ENTITY
                    && entityLabelsLeft > 0
                    && nodeScale > 0.75f
                    && radius > dp(5f)) {
                showLabel = true;
                entityLabelsLeft--;
            }
            if (showLabel) {
                String label = clipLabel(node.label, focused ? 28 : 14);
                labelPaint.setTextSize(sp(focused ? 12f : 9.5f));
                labelPaint.setTypeface(focused ? OrbeTokens.typeMedium() : OrbeTokens.typeLight());
                labelPaint.setColor(Color.argb((int) (110 * alphaMul), 0, 0, 0));
                canvas.drawText(label, p.x + dp(0.5f), p.y - radius - dp(5f) + dp(0.5f), labelPaint);
                labelPaint.setColor(Color.argb((int) ((focused ? 245 : 200) * alphaMul), 255, 255, 255));
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

    private void projectInto(float x, float y, float z, float cx, float cy, float scale,
            Projected out) {
        float xr = x * cosY - z * sinY;
        float zr = x * sinY + z * cosY;
        float yr = y * cosX - zr * sinX;
        float zf = y * sinX + zr * cosX;
        float depth = 1f / Math.max(MIN_DEPTH_DENOM, FOCAL + zf);
        float s = Math.min(scale * depth, scale * (1f / MIN_DEPTH_DENOM));
        out.x = cx + xr * s;
        out.y = cy + yr * s;
        out.depth = depth;
        out.scale = s;
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
        float x, y, depth, scale;
    }

    private static final class DrawNode {
        MemoryGraphScene.Node node;
        final Projected projected = new Projected();
    }
}
