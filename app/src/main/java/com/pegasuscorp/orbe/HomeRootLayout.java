package com.pegasuscorp.orbe;

import android.content.Context;
import android.view.MotionEvent;
import android.widget.FrameLayout;

/**
 * Route les touches : orbe / éventail OU tracé plein écran.
 */
public class HomeRootLayout extends FrameLayout {

    private OrbView orbView;
    private InkDrawingView inkZone;
    private AppDrawerPanel drawerPanel;
    private android.view.View activeTouchTarget;

    public HomeRootLayout(Context context) {
        super(context);
    }

    public void bind(OrbView orb, InkDrawingView ink, AppDrawerPanel drawer) {
        orbView = orb;
        inkZone = ink;
        drawerPanel = drawer;
    }

    /**
     * Libère la cible de touche courante. À appeler quand l'écran d'accueil reprend
     * la main (onResume/onPause) : sinon, si un geste a lancé une autre activité
     * (appui long -> picker, clic sur un raccourci -> app), le ACTION_UP n'est jamais
     * reçu, la cible reste figée, et au retour plus aucune touche n'aboutit.
     */
    public void resetTouch() {
        activeTouchTarget = null;
        if (inkZone != null) inkZone.cancelStroke();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) {
            resetTouch();
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        int action = ev.getActionMasked();

        // Tiroir ouvert : il gère tout. On libère toute cible restée en cours.
        if (drawerPanel != null && drawerPanel.isOpen()) {
            activeTouchTarget = null;
            return super.dispatchTouchEvent(ev);
        }

        if (action == MotionEvent.ACTION_DOWN) {
            // Nouveau geste : on repart toujours d'une cible propre.
            activeTouchTarget = null;
            if (orbView != null) {
                MotionEvent orbEvent = MotionEvent.obtain(ev);
                orbEvent.offsetLocation(-orbView.getLeft(), -orbView.getTop());
                if (orbView.shouldCaptureTouch(orbEvent)) {
                    activeTouchTarget = orbView;
                }
                orbEvent.recycle();
            }
            if (activeTouchTarget == null && inkZone != null) {
                activeTouchTarget = inkZone;
            }
        }

        if (activeTouchTarget != null) {
            MotionEvent targeted = MotionEvent.obtain(ev);
            targeted.offsetLocation(-activeTouchTarget.getLeft(), -activeTouchTarget.getTop());
            try {
                activeTouchTarget.dispatchTouchEvent(targeted);
            } finally {
                targeted.recycle();
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                activeTouchTarget = null;
            }
            return true;
        }

        return super.dispatchTouchEvent(ev);
    }

    @Override
    protected void onDetachedFromWindow() {
        // Sécurité : jamais de cible qui survit à la vue.
        activeTouchTarget = null;
        super.onDetachedFromWindow();
    }
}
