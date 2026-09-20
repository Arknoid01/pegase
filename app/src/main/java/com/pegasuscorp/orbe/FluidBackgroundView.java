package com.pegasuscorp.orbe;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * Fond d'accueil « Fluid » — dégradés selon l'heure ; drift animé optionnel.
 */
public class FluidBackgroundView extends View {

    private static final long DRIFT_MS = 14_000L;
    private static final long PHASE_TICK_MS = 60_000L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private ValueAnimator driftAnimator;
    private float drift;

    private FluidPhase.State phase = FluidPhase.current();
    private OrbThemes.Palette palette = OrbThemes.get(0);
    private boolean running;
    private boolean animateDrift;

    private final Runnable phaseTick = new Runnable() {
        @Override
        public void run() {
            refreshPhase();
            if (running && animateDrift) {
                main.postDelayed(this, PHASE_TICK_MS);
            }
        }
    };

    public FluidBackgroundView(Context context) {
        super(context);
        init();
    }

    public FluidBackgroundView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setWillNotDraw(false);
    }

    public void setAnimateDrift(boolean animateDrift) {
        this.animateDrift = animateDrift;
        if (running && !animateDrift) {
            stopDrift();
            drift = 0f;
            invalidate();
        } else if (running && animateDrift) {
            startDrift();
        }
    }

    public void setPalette(OrbThemes.Palette palette) {
        this.palette = palette != null ? palette : OrbThemes.get(0);
        refreshPhase();
    }

    public FluidPhase.State getPhase() {
        return phase;
    }

    public void refreshPhase() {
        phase = FluidPhase.tinted(FluidPhase.current(), palette);
        invalidate();
    }

    /** Affiche la phase courante — sans animation GPU continue. */
    public void startStatic() {
        animateDrift = false;
        if (running) {
            stopDrift();
            drift = 0f;
            refreshPhase();
            return;
        }
        running = true;
        drift = 0f;
        refreshPhase();
    }

    public void start() {
        if (running) {
            if (animateDrift) startDrift();
            else startStatic();
            return;
        }
        running = true;
        refreshPhase();
        if (animateDrift) {
            startDrift();
            main.removeCallbacks(phaseTick);
            main.postDelayed(phaseTick, PHASE_TICK_MS);
        } else {
            drift = 0f;
        }
    }

    public void stop() {
        running = false;
        stopDrift();
        main.removeCallbacks(phaseTick);
    }

    private void startDrift() {
        if (!animateDrift || driftAnimator != null) return;
        driftAnimator = ValueAnimator.ofFloat(0f, (float) (Math.PI * 2));
        driftAnimator.setDuration(DRIFT_MS);
        driftAnimator.setRepeatCount(ValueAnimator.INFINITE);
        driftAnimator.setInterpolator(new LinearInterpolator());
        driftAnimator.addUpdateListener(a -> {
            drift = (float) a.getAnimatedValue();
            invalidate();
        });
        driftAnimator.start();
    }

    private void stopDrift() {
        if (driftAnimator != null) {
            driftAnimator.cancel();
            driftAnimator = null;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        FluidRenderer.draw(canvas, getWidth(), getHeight(), phase, drift);
    }
}
