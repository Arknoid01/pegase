package com.pegasuscorp.orbe;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.AccelerateDecelerateInterpolator;

import androidx.core.content.ContextCompat;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Vue custom : orbe centrale + menu circulaire d'apps en raccourci.
 */
public class OrbView extends View {

    /** Emplacement de raccourci autour de l'orbe (icône ou vide). */
    public static class AppSlot {
        public final Drawable icon;
        float cx, cy;

        public AppSlot(Drawable icon) {
            this.icon = icon;
        }
    }

    public interface SlotListener {
        void onSlotClick(int index);
        void onSlotLongPress(int index);
    }

    private final List<AppSlot> appSlots = new ArrayList<>();
    private SlotListener slotListener;
    private Runnable onLongPress;
    private Runnable onSwipeUp;
    private Runnable onTripleTap;
    private Runnable onDoubleTap;
    private Runnable onPhaseClick;

    private static final long TRIPLE_TAP_WINDOW_MS = 550;
    private int orbTapCount;
    private long lastOrbTapTime;
    private Runnable pendingOrbTapAction;

    private final Paint orbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chipFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chipStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chipGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint plusPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint timePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint datePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint phasePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint haloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint orbitPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint particlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // --- Arc de chargement (mode réflexion de Pégase) ---
    private final Paint thinkArcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF thinkArcRect = new RectF();
    private float thinkArcAngle = 0f;       // angle de rotation (0→360)
    private float thinkArcAlpha = 0f;       // 0=invisible, 1=plein
    private boolean isThinking = false;
    private ValueAnimator thinkRotAnim;
    private ValueAnimator thinkFadeAnim;
    private Drawable wingDrawable;
    private final Paint wingGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private ValueAnimator wingFlightAnim;
    private ValueAnimator wingBreatheAnim;
    /** Ambient loops (pulse / halo / orbit / float / breathe) — pausables hors écran. */
    private final List<ValueAnimator> ambientAnimators = new ArrayList<>();
    private boolean ambientPaused = false;
    private long lastAmbientInvalidateMs;

    private static class WingParticle {
        float x, y, vx, vy, life, maxLife, size;
    }

    private final List<WingParticle> wingParticles = new ArrayList<>();
    private long lastParticleUpdateNs = 0L;
    private boolean wingListening = false;
    private float wingBreathePhase = 0f;
    private float wingAlphaSmoothed = 32f;
    private float prevFlightBeat1 = 0f;
    private float prevFlightBeat2 = 0f;

    private final Runnable wingFlutterTick = new Runnable() {
        @Override
        public void run() {
            if (isWingFlutterActive()
                    && (wingFlightAnim == null || !wingFlightAnim.isRunning())) {
                invalidate();
                postOnAnimationDelayed(this, 16);
            }
        }
    };

    private float orbRadius;
    private float chipRadius;
    private float expandProgress = 0f;  // 0 = ferme, 1 = ouvert
    private float orbPulse = 0f;
    private final float[] haloPulse = new float[3];
    private float orbitAngle = 0f;
    private float slotFloatPhase = 0f;
    private float wingDeploy = 0f;      // 0 = repos, 1 = déployé
    private float wingLift = 0f;        // soulèvement vertical (envol)
    private float wingSpread = 1f;      // écartement horizontal
    private float wingFlapDeg = 0f;     // battement gauche/droite
    private boolean expanded = false;

    private final Handler touchHandler = new Handler(Looper.getMainLooper());
    private final int touchSlop;
    private float downX, downY;
    private boolean longPressFired;
    private Runnable longPressRunnable;

    private static final float SHORTCUT_RING_FACTOR = 3.15f;

    // Couleurs officielles Pégase (README)
    private int orbColorCore = Color.parseColor("#B8FBF6");
    private int orbColorMiddle = Color.parseColor("#35D0DD");
    private int orbColorEdge = Color.parseColor("#0B7D8F");

    private final GestureDetector flingDetector;

    public OrbView(Context ctx) {
        super(ctx);
        touchSlop = ViewConfiguration.get(ctx).getScaledTouchSlop();

        chipFillPaint.setColor(Color.parseColor("#2A0B0E14"));
        chipStrokePaint.setStyle(Paint.Style.STROKE);
        chipStrokePaint.setStrokeWidth(1.5f);
        chipStrokePaint.setColor(Color.parseColor("#6635D0DD"));

        chipGlowPaint.setStyle(Paint.Style.FILL);
        chipGlowPaint.setColor(Color.parseColor("#2835D0DD"));

        plusPaint.setColor(Color.parseColor("#80FFFFFF"));
        plusPaint.setTextAlign(Paint.Align.CENTER);
        plusPaint.setFakeBoldText(true);

        timePaint.setColor(Color.WHITE);
        timePaint.setTextAlign(Paint.Align.CENTER);
        timePaint.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));

        datePaint.setColor(Color.WHITE);
        datePaint.setAlpha(200);
        datePaint.setTextAlign(Paint.Align.CENTER);
        datePaint.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));

        phasePaint.setColor(Color.WHITE);
        phasePaint.setAlpha(210);
        phasePaint.setTextAlign(Paint.Align.CENTER);
        phasePaint.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        phasePaint.setUnderlineText(true);

        haloPaint.setStyle(Paint.Style.STROKE);
        haloPaint.setStrokeWidth(1.5f);
        haloPaint.setColor(orbColorMiddle);

        orbitPaint.setColor(orbColorMiddle);
        orbitPaint.setAlpha(120);

        // Arc de chargement : trait cyan semi-transparent, style STROKE
        thinkArcPaint.setStyle(Paint.Style.STROKE);
        thinkArcPaint.setStrokeCap(Paint.Cap.ROUND);
        thinkArcPaint.setColor(Color.parseColor("#9935D0DD"));
        thinkArcPaint.setStrokeWidth(6f);

        particlePaint.setColor(Color.WHITE);
        particlePaint.setAlpha(100);

        wingGlowPaint.setStyle(Paint.Style.FILL);

        wingDrawable = ContextCompat.getDrawable(ctx, R.drawable.pegasus_wings);
        updateWingTint();

        setClickable(true);
        setFocusable(true);

        startAnimations();

        flingDetector = new GestureDetector(ctx, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (vy < -1500 && onSwipeUp != null && isOnOrb(e1.getX(), e1.getY())) {
                    onSwipeUp.run();
                    return true;
                }
                return false;
            }
        });
    }

    public void setAppSlots(List<AppSlot> list) {
        appSlots.clear();
        appSlots.addAll(list);
        invalidate();
    }

    public void setSlotListener(SlotListener listener) {
        this.slotListener = listener;
    }

    public void setOnLongPress(Runnable r) { this.onLongPress = r; }
    public void setOnSwipeUp(Runnable r)   { this.onSwipeUp = r; }
    public void setOnTripleTap(Runnable r)  { this.onTripleTap = r; }
    public void setOnDoubleTap(Runnable r)  { this.onDoubleTap = r; }
    public void setOnPhaseClick(Runnable r) { this.onPhaseClick = r; }

    public void setOrbColors(int core, int middle, int edge) {
        this.orbColorCore = core;
        this.orbColorMiddle = middle;
        this.orbColorEdge = edge;
        haloPaint.setColor(middle);
        orbitPaint.setColor(middle);
        chipStrokePaint.setColor(Color.argb(0x66, Color.red(middle), Color.green(middle), Color.blue(middle)));
        chipGlowPaint.setColor(Color.argb(0x28, Color.red(middle), Color.green(middle), Color.blue(middle)));
        updateWingTint();
        updateOrbShader();
        invalidate();
    }

    private void updateWingTint() {
        if (wingDrawable != null) {
            wingDrawable.setColorFilter(
                    new PorterDuffColorFilter(orbColorMiddle, PorterDuff.Mode.SRC_IN));
        }
    }

    private void updateOrbShader() {
        updateOrbShader(orbRadius);
    }

    private void updateOrbShader(float drawRadius) {
        float cx = getOrbCx(), cy = getOrbCy();
        if (cx <= 0 || drawRadius <= 0) return;
        int core = brightenColor(orbColorCore, orbPulse * 0.2f);
        orbPaint.setShader(new RadialGradient(
                cx, cy, drawRadius,
                new int[]{core, orbColorMiddle, orbColorEdge},
                new float[]{0f, 0.52f + orbPulse * 0.06f, 1f},
                Shader.TileMode.CLAMP));
    }

    private static int brightenColor(int color, float amount) {
        int a = Color.alpha(color);
        int r = Math.min(255, (int) (Color.red(color) + 255f * amount));
        int g = Math.min(255, (int) (Color.green(color) + 255f * amount));
        int b = Math.min(255, (int) (Color.blue(color) + 255f * amount));
        return Color.argb(a, r, g, b);
    }

    private float getOrbCx() {
        return getPaddingLeft() + (getWidth() - getPaddingLeft() - getPaddingRight()) / 2f;
    }

    /** Orbe centrée verticalement dans la zone utile. */
    private float getOrbCy() {
        return getPaddingTop() + contentHeight() * 0.50f;
    }

    private float contentHeight() {
        return getHeight() - getPaddingTop() - getPaddingBottom();
    }

    private float getShortcutMenuTopY() {
        float cy = getOrbCy();
        float ringR = orbRadius * SHORTCUT_RING_FACTOR;
        float topSlotCy = cy - ringR;
        return topSlotCy - chipRadius * 1.5f;
    }

    private float getTimeY() {
        float anchorDateY = getShortcutMenuTopY() - orbRadius * 0.06f;
        float gapFromDate = timePaint.getTextSize() * 0.65f + orbRadius * 0.22f;
        return anchorDateY - datePaint.getTextSize() - gapFromDate;
    }

    private float getDateY() {
        return getShortcutMenuTopY() - orbRadius * 0.67f;
    }

    private static String formatFrenchDate() {
        String raw = new SimpleDateFormat("EEEE d MMMM", Locale.FRENCH).format(new Date());
        return raw.toLowerCase(Locale.FRENCH);
    }

    private float getPhaseY() {
        return getPaddingTop() + contentHeight() * 0.93f;
    }

    private String getPhaseLabel() {
        return HomeDailyLine.forToday(getContext());
    }

    private boolean isOnPhaseLabel(float x, float y) {
        String text = getPhaseLabel();
        float cx = getOrbCx();
        float baseline = getPhaseY();
        float halfW = phasePaint.measureText(text) / 2f + touchSlop * 2f;
        float top = baseline - phasePaint.getTextSize();
        float bottom = baseline + phasePaint.getTextSize() * 0.35f;
        return Math.abs(x - cx) < halfW && y >= top && y <= bottom;
    }

    private boolean isOnOrb(float x, float y) {
        return dist(x, y, getOrbCx(), getOrbCy()) < orbRadius * 1.25f;
    }

    private boolean isOnShortcut(float x, float y) {
        return expanded && hitSlot(x, y) >= 0;
    }

    private void layoutSlot(int index, int count, float cx, float cy, float[] out) {
        double ang = Math.toRadians(-90 + (360.0 / count) * index);
        float bob = (float) Math.sin((slotFloatPhase + index * 0.55f) * Math.PI * 2)
                * orbRadius * 0.045f * expandProgress;
        float ringR = orbRadius * SHORTCUT_RING_FACTOR * expandProgress + bob;
        out[0] = cx + (float) Math.cos(ang) * ringR;
        out[1] = cy + (float) Math.sin(ang) * ringR;
    }

    private int hitSlot(float x, float y) {
        if (appSlots.isEmpty() || expandProgress <= 0f) return -1;
        float cx = getOrbCx(), cy = getOrbCy();
        int n = appSlots.size();
        float[] pos = new float[2];
        for (int i = 0; i < n; i++) {
            layoutSlot(i, n, cx, cy, pos);
            if (dist(x, y, pos[0], pos[1]) < chipRadius * 1.15f) return i;
        }
        return -1;
    }

    public boolean collapseIfExpanded() {
        if (expanded) { collapse(); return true; }
        return false;
    }

    public boolean isFanExpanded() {
        return expanded;
    }

    /** Détermine si l'orbe doit capter ce geste (pas le tracé plein écran). */
    public boolean shouldCaptureTouch(MotionEvent e) {
        if (expanded) return true;
        return isOnOrb(e.getX(), e.getY()) || isOnShortcut(e.getX(), e.getY())
                || isOnPhaseLabel(e.getX(), e.getY());
    }

    // ---------------------------------------------------------------------

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        orbRadius = Math.min(w, h) * 0.125f;
        chipRadius = orbRadius * 0.46f;
        plusPaint.setTextSize(chipRadius * 0.9f);
        timePaint.setTextSize(h * 0.068f);
        datePaint.setTextSize(h * 0.024f);
        phasePaint.setTextSize(h * 0.022f);

        updateOrbShader();
    }

    @Override
    protected void onDraw(Canvas c) {
        float cx = getOrbCx(), cy = getOrbCy();

        // --- Horloge et date (haut d'écran, comme la maquette) ---
        String time = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
        String date = formatFrenchDate();
        c.drawText(time, cx, getTimeY(), timePaint);
        c.drawText(date, cx, getDateY(), datePaint);

        // --- 3 halos pulsants autour de l'orbe ---
        float[] haloRadii = {1.42f, 1.88f, 2.38f};
        for (int i = 0; i < haloRadii.length; i++) {
            float pulse = haloPulse[i];
            float scale = 1f + pulse * 0.08f;
            int alpha = (int) (28 + pulse * 72);
            haloPaint.setAlpha(alpha);
            haloPaint.setStrokeWidth(1.5f + pulse * 1.2f);
            c.drawCircle(cx, cy, orbRadius * haloRadii[i] * scale, haloPaint);
        }

        // --- Ailes très discrètes au repos ---
        drawWings(c, cx, cy);

        // --- Petites orbes orbitales : derrière l'orbe centrale ---
        drawOrbitingParticles(c, cx, cy, false);

        // --- Raccourcis au-dessus de l'horizon (derrière l'orbe) ---
        if (expandProgress > 0f && !appSlots.isEmpty()) {
            layoutAllSlots(cx, cy);
            drawAppSlotsLayer(c, cy, false);
        }

        // L'orbe centrale (pulse respirant)
        drawMainOrb(c, cx, cy);

        // --- Arc de chargement (Pégase réfléchit) ---
        if (thinkArcAlpha > 0f) {
            float r = orbRadius * 1.18f;
            thinkArcRect.set(cx - r, cy - r, cx + r, cy + r);
            thinkArcPaint.setAlpha((int) (thinkArcAlpha * 200));
            thinkArcPaint.setStrokeWidth(orbRadius * 0.06f);
            // Arc de 120° qui tourne
            c.drawArc(thinkArcRect, thinkArcAngle, 120f, false, thinkArcPaint);
            // Petit arc opposé plus discret
            thinkArcPaint.setAlpha((int) (thinkArcAlpha * 80));
            c.drawArc(thinkArcRect, thinkArcAngle + 180f, 60f, false, thinkArcPaint);
        }

        // --- Petites orbes orbitales : devant l'orbe centrale ---
        drawOrbitingParticles(c, cx, cy, true);

        // --- Raccourcis sous l'horizon (devant l'orbe) ---
        if (expandProgress > 0f && !appSlots.isEmpty()) {
            drawAppSlotsLayer(c, cy, true);
        }

        // --- Message d'ambiance cliquable → discussion texte ---
        c.drawText(getPhaseLabel(), cx, getPhaseY(), phasePaint);
    }

    private void layoutAllSlots(float cx, float cy) {
        int n = appSlots.size();
        float[] pos = new float[2];
        for (int i = 0; i < n; i++) {
            layoutSlot(i, n, cx, cy, pos);
            AppSlot slot = appSlots.get(i);
            slot.cx = pos[0];
            slot.cy = pos[1];
        }
    }

    private void drawAppSlotsLayer(Canvas c, float orbCy, boolean inFront) {
        for (int i = 0; i < appSlots.size(); i++) {
            AppSlot slot = appSlots.get(i);
            boolean slotInFront = slot.cy > orbCy;
            if (slotInFront == inFront) {
                drawAppSlot(c, slot, i);
            }
        }
    }

    private void drawAppSlot(Canvas c, AppSlot slot, int index) {
        float r = chipRadius * expandProgress;
        if (r < 4f) return;

        float glowPulse = 0.85f + 0.15f * (float) Math.sin((slotFloatPhase + index * 0.4f) * Math.PI * 2);
        chipGlowPaint.setAlpha((int) (40 * expandProgress * glowPulse));
        c.drawCircle(slot.cx, slot.cy, r * 1.45f, chipGlowPaint);
        chipGlowPaint.setAlpha((int) (24 * expandProgress * glowPulse));
        c.drawCircle(slot.cx, slot.cy, r * 1.2f, chipGlowPaint);

        chipFillPaint.setAlpha((int) (120 * expandProgress));
        c.drawCircle(slot.cx, slot.cy, r, chipFillPaint);
        chipStrokePaint.setAlpha((int) (140 * expandProgress));
        c.drawCircle(slot.cx, slot.cy, r, chipStrokePaint);

        if (slot.icon != null) {
            int alpha = (int) (255 * expandProgress);
            slot.icon.setAlpha(alpha);
            int iconSize = (int) (r * 1.5f);
            int left = (int) (slot.cx - iconSize / 2f);
            int top = (int) (slot.cy - iconSize / 2f);
            slot.icon.setBounds(left, top, left + iconSize, top + iconSize);
            slot.icon.draw(c);
        } else {
            plusPaint.setAlpha((int) (130 * expandProgress));
            c.drawText("+", slot.cx, slot.cy + plusPaint.getTextSize() * 0.35f, plusPaint);
        }
    }

    private void drawMainOrb(Canvas c, float cx, float cy) {
        float scale = 1f + orbPulse * 0.075f;
        float r = orbRadius * scale;

        updateOrbShader(r);
        orbPaint.setAlpha((int) (232 + orbPulse * 23));
        c.drawCircle(cx, cy, r, orbPaint);
        orbPaint.setAlpha(255);
    }

    /** Dessine les orbes orbitales et points fixes, derrière ou devant l'orbe centrale. */
    private void drawOrbitingParticles(Canvas c, float cx, float cy, boolean inFront) {
        float orbitR = orbRadius * 2.4f;
        float particleR = orbRadius * 0.07f;
        for (int i = 0; i < 5; i++) {
            double angle = Math.toRadians(orbitAngle + (i * 72));
            float px = cx + (float) Math.cos(angle) * orbitR;
            float py = cy + (float) Math.sin(angle) * orbitR * 0.35f;
            boolean particleInFront = py > cy;
            if (particleInFront == inFront) {
                c.drawCircle(px, py, particleR, orbitPaint);
            }
        }
        float[][] staticDots = {
                {0.55f, -0.25f, 0.04f}, {0.9f, 0.1f, 0.03f}, {-0.7f, 0.2f, 0.035f},
                {0.2f, 0.55f, 0.03f}, {-0.4f, -0.45f, 0.025f}, {0.75f, -0.5f, 0.03f}
        };
        for (float[] dot : staticDots) {
            boolean dotInFront = dot[1] > 0f;
            if (dotInFront == inFront) {
                c.drawCircle(cx + orbRadius * dot[0], cy + orbRadius * dot[1],
                        orbRadius * dot[2], particlePaint);
            }
        }
    }

    private boolean isWingFlutterActive() {
        return isThinking || wingListening;
    }

    private void updateWingFlutterLoop() {
        removeCallbacks(wingFlutterTick);
        if (isWingFlutterActive()) {
            post(wingFlutterTick);
        }
    }

    private void tickWingParticles() {
        if (wingParticles.isEmpty()) {
            lastParticleUpdateNs = 0L;
            return;
        }
        long now = System.nanoTime();
        float dt = lastParticleUpdateNs == 0L ? 0.016f
                : Math.min(0.05f, (now - lastParticleUpdateNs) / 1_000_000_000f);
        lastParticleUpdateNs = now;

        Iterator<WingParticle> it = wingParticles.iterator();
        while (it.hasNext()) {
            WingParticle p = it.next();
            p.life -= dt;
            if (p.life <= 0f) {
                it.remove();
                continue;
            }
            p.x += p.vx * dt;
            p.y += p.vy * dt;
            p.vx *= 1f - dt * 0.9f;
            p.vy -= orbRadius * 0.12f * dt;
        }
        if (!wingParticles.isEmpty()) {
            postInvalidateOnAnimation();
        }
    }

    private void spawnDeployParticles(float cx, float cy, float intensity) {
        if (orbRadius <= 0f) return;
        int count = (int) (8 + intensity * 10);
        for (int i = 0; i < count; i++) {
            WingParticle p = new WingParticle();
            float angle = (float) (Math.random() * Math.PI * 2);
            float dist = orbRadius * (0.15f + (float) Math.random() * 0.45f);
            p.x = cx + (float) Math.cos(angle) * dist;
            p.y = cy + orbRadius * 0.08f + (float) Math.sin(angle) * dist * 0.35f;
            float speed = orbRadius * (0.3f + (float) Math.random() * 0.4f);
            p.vx = (float) Math.cos(angle) * speed * 0.55f;
            p.vy = (float) Math.sin(angle) * speed * 0.25f - orbRadius * 0.1f;
            p.maxLife = 0.4f + (float) Math.random() * 0.35f;
            p.life = p.maxLife;
            p.size = orbRadius * (0.03f + (float) Math.random() * 0.04f);
            wingParticles.add(p);
        }
        postInvalidateOnAnimation();
    }

    private void drawWingParticles(Canvas c) {
        for (WingParticle p : wingParticles) {
            float a = p.life / p.maxLife;
            particlePaint.setColor(orbColorCore);
            particlePaint.setAlpha((int) (a * a * 120));
            c.drawCircle(p.x, p.y, p.size * (0.45f + 0.55f * a), particlePaint);
        }
    }

    private void drawWings(Canvas c, float cx, float cy) {
        if (wingDrawable == null) return;

        tickWingParticles();

        float deploy;
        float lift;
        float spread;
        float flapDeg;
        boolean flying = wingFlightAnim != null && wingFlightAnim.isRunning();
        if (flying) {
            deploy = wingDeploy;
            lift = wingLift;
            spread = wingSpread;
            flapDeg = wingFlapDeg;
        } else if (isWingFlutterActive()) {
            float cycle = (SystemClock.uptimeMillis() % 2400L) / 2400f;
            float wave = (float) Math.sin(cycle * Math.PI * 2);
            float wave2 = (float) Math.sin(cycle * Math.PI * 4);
            deploy = 0.30f + 0.20f * Math.abs(wave);
            lift = deploy * 0.28f;
            spread = 1f + deploy * 0.38f;
            flapDeg = 9f * wave2;
        } else {
            float breathe = (float) Math.sin(wingBreathePhase * Math.PI * 2);
            deploy = 0.07f + breathe * 0.022f;
            lift = deploy * 0.12f;
            spread = 1f + deploy * 0.08f;
            flapDeg = breathe * 2.2f;
        }

        float targetAlpha = (flying || isWingFlutterActive())
                ? 55f + deploy * 175f
                : 24f + deploy * 70f;
        wingAlphaSmoothed += (targetAlpha - wingAlphaSmoothed) * 0.14f;
        wingDrawable.setAlpha((int) Math.max(8f, Math.min(255f, wingAlphaSmoothed)));

        if (deploy > 0.08f) {
            drawWingGlow(c, cx, cy - lift * orbRadius * 0.35f, deploy);
        }

        drawWingParticles(c);

        float wingW = orbRadius * (6.6f + deploy * 2.2f);
        float wingH = orbRadius * (3.7f + deploy * 1.5f);
        drawWingHalf(c, cx, cy, wingW, wingH, lift, spread, flapDeg, true);
        drawWingHalf(c, cx, cy, wingW, wingH, lift, spread, flapDeg, false);
    }

    private void drawWingGlow(Canvas c, float cx, float cy, float deploy) {
        float r = orbRadius * (2.2f + deploy * 1.4f);
        wingGlowPaint.setShader(new RadialGradient(
                cx, cy, r,
                new int[]{
                        Color.argb((int) (deploy * 55), Color.red(orbColorMiddle),
                                Color.green(orbColorMiddle), Color.blue(orbColorMiddle)),
                        Color.TRANSPARENT
                },
                new float[]{0.2f, 1f},
                Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r, wingGlowPaint);
        wingGlowPaint.setShader(null);
    }

    /** Demi-aile avec battement asymétrique (gauche/droite opposés). */
    private void drawWingHalf(Canvas c, float cx, float cy,
                              float wingW, float wingH,
                              float lift, float spread, float flapDeg, boolean left) {
        int hw = (int) (wingW / 2f);
        int hh = (int) (wingH / 2f);

        c.save();
        c.translate(cx, cy - lift * orbRadius * 0.45f);
        c.scale(spread, 1f + lift * 0.35f);
        c.rotate(flapDeg * (left ? -1f : 1f));
        if (left) {
            c.clipRect(-hw - 4, -hh - 4, 4, hh + 4);
        } else {
            c.clipRect(-4, -hh - 4, hw + 4, hh + 4);
        }
        wingDrawable.setBounds(-hw, -hh, hw, hh);
        wingDrawable.draw(c);
        c.restore();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        flingDetector.onTouchEvent(e);

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                longPressFired = false;
                touchHandler.removeCallbacks(longPressRunnable);
                longPressRunnable = () -> {
                    longPressFired = true;
                    if (expanded) {
                        int slot = hitSlot(downX, downY);
                        if (slot >= 0 && slotListener != null) {
                            slotListener.onSlotLongPress(slot);
                            return;
                        }
                    }
                    if (isOnOrb(downX, downY) && onLongPress != null) {
                        collapse();
                        onLongPress.run();
                    }
                };
                touchHandler.postDelayed(longPressRunnable, 550L);
                return true;

            case MotionEvent.ACTION_MOVE:
                if (dist(e.getX(), e.getY(), downX, downY) > touchSlop) {
                    touchHandler.removeCallbacks(longPressRunnable);
                }
                return true;

            case MotionEvent.ACTION_UP:
                touchHandler.removeCallbacks(longPressRunnable);
                if (!longPressFired) {
                    handleTap(downX, downY);
                }
                return true;

            case MotionEvent.ACTION_CANCEL:
                touchHandler.removeCallbacks(longPressRunnable);
                return true;

            default:
                return true;
        }
    }

    private void handleTap(float x, float y) {
        if (!expanded && isOnPhaseLabel(x, y)) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            if (onPhaseClick != null) onPhaseClick.run();
            return;
        }
        if (expanded) {
            int slot = hitSlot(x, y);
            if (slot >= 0 && slotListener != null) {
                slotListener.onSlotClick(slot);
                return;
            }
            if (!isOnOrb(x, y)) {
                collapse();
            }
            return;
        }
        if (isOnOrb(x, y)) {
            handleOrbTap();
        }
    }

    private void handleOrbTap() {
        touchHandler.removeCallbacks(pendingOrbTapAction);
        long now = System.currentTimeMillis();
        if (now - lastOrbTapTime > TRIPLE_TAP_WINDOW_MS) {
            orbTapCount = 0;
        }
        orbTapCount++;
        lastOrbTapTime = now;

        if (orbTapCount >= 3) {
            orbTapCount = 0;
            if (onTripleTap != null) onTripleTap.run();
            return;
        }

        if (orbTapCount == 2) {
            orbTapCount = 0;
            touchHandler.removeCallbacks(pendingOrbTapAction);
            pendingOrbTapAction = null;
            if (onDoubleTap != null) onDoubleTap.run();
            return;
        }

        pendingOrbTapAction = () -> {
            if (orbTapCount == 1) {
                expand();
            }
            orbTapCount = 0;
        };
        touchHandler.postDelayed(pendingOrbTapAction, TRIPLE_TAP_WINDOW_MS);
    }

    /** Pause CPU : à appeler depuis MainActivity.onStop (app au-dessus). */
    public void pauseAmbient() {
        ambientPaused = true;
        for (ValueAnimator a : ambientAnimators) {
            if (a != null && a.isRunning()) a.pause();
        }
        removeCallbacks(wingFlutterTick);
        if (thinkRotAnim != null && thinkRotAnim.isRunning()) thinkRotAnim.pause();
        if (wingFlightAnim != null && wingFlightAnim.isRunning()) wingFlightAnim.pause();
    }

    /** Reprend les loops ambient (retour HOME). */
    public void resumeAmbient() {
        if (!ambientPaused) {
            invalidate();
            return;
        }
        ambientPaused = false;
        for (ValueAnimator a : ambientAnimators) {
            if (a != null) a.resume();
        }
        if (thinkRotAnim != null) thinkRotAnim.resume();
        if (wingFlightAnim != null) wingFlightAnim.resume();
        updateWingFlutterLoop();
        invalidate();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) {
            if (ambientPaused) resumeAmbient();
        } else {
            pauseAmbient();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        touchHandler.removeCallbacksAndMessages(null);
        removeCallbacks(wingFlutterTick);
        for (ValueAnimator a : ambientAnimators) {
            if (a != null) a.cancel();
        }
        ambientAnimators.clear();
        if (wingFlightAnim != null) wingFlightAnim.cancel();
        if (wingBreatheAnim != null) wingBreatheAnim.cancel();
        if (thinkRotAnim != null) thinkRotAnim.cancel();
        if (thinkFadeAnim != null) thinkFadeAnim.cancel();
        super.onDetachedFromWindow();
    }

    /** ~15 fps hors interaction (thinking / listening / expand gardent full rate). */
    private void invalidateAmbient() {
        if (ambientPaused) return;
        if (isThinking || wingListening || expanded
                || (wingFlightAnim != null && wingFlightAnim.isRunning())) {
            invalidate();
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now - lastAmbientInvalidateMs < 66L) return;
        lastAmbientInvalidateMs = now;
        invalidate();
    }

    // ---------------------------------------------------------------------

    private void expand() {
        expanded = true;
        animateTo(1f);
    }

    private void collapse() {
        expanded = false;
        animateTo(0f);
    }

    private void animateTo(float target) {
        ValueAnimator a = ValueAnimator.ofFloat(expandProgress, target);
        a.setDuration(220);
        a.addUpdateListener(v -> {
            expandProgress = (float) v.getAnimatedValue();
            invalidate();
        });
        a.start();
    }

    private void startAnimations() {
        ambientAnimators.clear();

        ValueAnimator pulse = ValueAnimator.ofFloat(0f, 1f);
        pulse.setDuration(2800);
        pulse.setRepeatMode(ValueAnimator.REVERSE);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        pulse.setInterpolator(new AccelerateDecelerateInterpolator());
        pulse.addUpdateListener(v -> {
            orbPulse = (float) v.getAnimatedValue();
            invalidateAmbient();
        });
        pulse.start();
        ambientAnimators.add(pulse);

        for (int i = 0; i < haloPulse.length; i++) {
            final int ring = i;
            ValueAnimator halo = ValueAnimator.ofFloat(0f, 1f);
            halo.setDuration(2400);
            halo.setRepeatMode(ValueAnimator.REVERSE);
            halo.setRepeatCount(ValueAnimator.INFINITE);
            halo.setStartDelay(i * 500L);
            halo.addUpdateListener(v -> {
                haloPulse[ring] = (float) v.getAnimatedValue();
                invalidateAmbient();
            });
            halo.start();
            ambientAnimators.add(halo);
        }

        ValueAnimator slotFloat = ValueAnimator.ofFloat(0f, 1f);
        slotFloat.setDuration(3400);
        slotFloat.setRepeatCount(ValueAnimator.INFINITE);
        slotFloat.setInterpolator(null);
        slotFloat.addUpdateListener(v -> {
            slotFloatPhase = (float) v.getAnimatedValue();
            if (expanded) invalidate();
        });
        slotFloat.start();
        ambientAnimators.add(slotFloat);

        // Rotation des petites sphères — plein débit (pas le throttle ambient 15 fps)
        ValueAnimator orbit = ValueAnimator.ofFloat(0f, 360f);
        orbit.setDuration(4800);
        orbit.setRepeatCount(ValueAnimator.INFINITE);
        orbit.setInterpolator(new android.view.animation.LinearInterpolator());
        orbit.addUpdateListener(v -> {
            orbitAngle = (float) v.getAnimatedValue();
            if (!ambientPaused) {
                postInvalidateOnAnimation();
            }
        });
        orbit.start();
        ambientAnimators.add(orbit);

        wingBreatheAnim = ValueAnimator.ofFloat(0f, 1f);
        wingBreatheAnim.setDuration(5200);
        wingBreatheAnim.setRepeatCount(ValueAnimator.INFINITE);
        wingBreatheAnim.setRepeatMode(ValueAnimator.REVERSE);
        wingBreatheAnim.setInterpolator(new AccelerateDecelerateInterpolator());
        wingBreatheAnim.addUpdateListener(v -> {
            wingBreathePhase = (float) v.getAnimatedValue();
            if ((wingFlightAnim == null || !wingFlightAnim.isRunning()) && !isWingFlutterActive()) {
                invalidateAmbient();
            }
        });
        wingBreatheAnim.start();
        ambientAnimators.add(wingBreatheAnim);
    }

    /** Battement continu pendant l'écoute vocale. */
    public void setListening(boolean listening) {
        if (wingListening == listening) return;
        wingListening = listening;
        updateWingFlutterLoop();
        invalidate();
    }

    /** Active l'arc de chargement tournant (Pégase réfléchit). */
    public void setThinking(boolean thinking) {
        if (isThinking == thinking) return;
        isThinking = thinking;
        updateWingFlutterLoop();

        // Arrête les anims précédentes
        if (thinkRotAnim != null) thinkRotAnim.cancel();
        if (thinkFadeAnim != null) thinkFadeAnim.cancel();

        if (thinking) {
            // Rotation continue
            thinkRotAnim = ValueAnimator.ofFloat(0f, 360f);
            thinkRotAnim.setDuration(1800);
            thinkRotAnim.setRepeatCount(ValueAnimator.INFINITE);
            thinkRotAnim.setInterpolator(new android.view.animation.LinearInterpolator());
            thinkRotAnim.addUpdateListener(a -> {
                thinkArcAngle = (float) a.getAnimatedValue();
                invalidate();
            });
            thinkRotAnim.start();
            // Fade in
            thinkFadeAnim = ValueAnimator.ofFloat(thinkArcAlpha, 1f);
            thinkFadeAnim.setDuration(400);
            thinkFadeAnim.addUpdateListener(a -> thinkArcAlpha = (float) a.getAnimatedValue());
            thinkFadeAnim.start();
        } else {
            // Fade out puis stop
            thinkFadeAnim = ValueAnimator.ofFloat(thinkArcAlpha, 0f);
            thinkFadeAnim.setDuration(500);
            thinkFadeAnim.addUpdateListener(a -> {
                thinkArcAlpha = (float) a.getAnimatedValue();
                invalidate();
            });
            thinkFadeAnim.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator anim) {
                    if (thinkRotAnim != null) thinkRotAnim.cancel();
                }
            });
            thinkFadeAnim.start();
        }
    }

    /** Déploie les ailes — double battement puis envol (réveil / discussion). */
    public void deployWings() {
        if (wingFlightAnim != null) wingFlightAnim.cancel();
        prevFlightBeat1 = 0f;
        prevFlightBeat2 = 0f;
        float cx = getOrbCx(), cy = getOrbCy();
        if (cx > 0f && orbRadius > 0f) {
            spawnDeployParticles(cx, cy, 0.85f);
        }
        wingFlightAnim = ValueAnimator.ofFloat(0f, 1f);
        wingFlightAnim.setDuration(2400);
        wingFlightAnim.setInterpolator(new AccelerateDecelerateInterpolator());
        wingFlightAnim.addUpdateListener(anim -> {
            applyWingFlightFrame((float) anim.getAnimatedValue());
            invalidate();
        });
        wingFlightAnim.start();
    }

    /**
     * Courbe d'envol : 2 battements amortis + léger soulèvement puis repli.
     */
    private void applyWingFlightFrame(float t) {
        float beat1 = wingBell(t / 0.32f) * wingGate(t, 0f, 0.38f);
        float beat2 = wingBell((t - 0.28f) / 0.28f) * wingGate(t, 0.28f, 0.58f) * 0.72f;
        float glide = (1f - wingSmoothstep(0.55f, 1f, t)) * 0.35f;
        wingDeploy = Math.min(1f, beat1 + beat2 + glide);

        float envelope = t < 0.7f
                ? wingSmoothstep(0f, 0.15f, t)
                : 1f - wingSmoothstep(0.7f, 1f, t);
        wingDeploy *= Math.max(0.15f, envelope);

        wingLift = wingDeploy * (0.42f + 0.25f * (float) Math.sin(t * Math.PI));
        wingSpread = 1f + wingDeploy * 0.58f;
        wingFlapDeg = wingDeploy * 18f * (float) Math.sin(t * Math.PI * 5.2);

        float cx = getOrbCx(), cy = getOrbCy();
        if (cx > 0f && orbRadius > 0f) {
            if (beat1 > 0.72f && prevFlightBeat1 <= 0.72f) {
                spawnDeployParticles(cx, cy, wingDeploy);
            }
            if (beat2 > 0.72f && prevFlightBeat2 <= 0.72f) {
                spawnDeployParticles(cx, cy, wingDeploy * 0.75f);
            }
        }
        prevFlightBeat1 = beat1;
        prevFlightBeat2 = beat2;
    }

    private static float wingBell(float x) {
        if (x <= 0f || x >= 1f) return 0f;
        return (float) Math.sin(x * Math.PI);
    }

    private static float wingGate(float t, float start, float end) {
        if (t < start || t > end) return 0f;
        return 1f;
    }

    private static float wingSmoothstep(float edge0, float edge1, float x) {
        if (x <= edge0) return 0f;
        if (x >= edge1) return 1f;
        float t = (x - edge0) / (edge1 - edge0);
        return t * t * (3f - 2f * t);
    }

    private static float dist(float x1, float y1, float x2, float y2) {
        return (float) Math.hypot(x1 - x2, y1 - y2);
    }
}
