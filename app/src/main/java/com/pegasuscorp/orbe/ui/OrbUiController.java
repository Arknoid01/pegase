package com.pegasuscorp.orbe.ui;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.view.View;
import android.widget.TextView;

import com.pegasuscorp.orbe.AppDrawerPanel;
import com.pegasuscorp.orbe.ChargingThreadView;
import com.pegasuscorp.orbe.FluidBackgroundView;
import com.pegasuscorp.orbe.FluidLockWallpaperSync;
import com.pegasuscorp.orbe.GestureHintsStore;
import com.pegasuscorp.orbe.HomeVeilView;
import com.pegasuscorp.orbe.HomeWallpaperView;
import com.pegasuscorp.orbe.IconThemeHelper;
import com.pegasuscorp.orbe.InkDrawingView;
import com.pegasuscorp.orbe.OrbThemes;
import com.pegasuscorp.orbe.OrbView;
import com.pegasuscorp.orbe.PersonalizationStore;
import com.pegasuscorp.orbe.ShortcutStore;
import com.pegasuscorp.orbe.voice.PegaseVisualPhase;
import com.pegasuscorp.orbe.voice.WakeHealthStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * Contrôleur UI orbe : hints gestes, slots raccourcis, personnalisation couleurs.
 */
public final class OrbUiController {

    private final Context context;
    private final OrbView orbView;
    private final TextView gestureHintView;
    private final Handler mainHandler;
    private final InkDrawingView inkZone;
    private final ChargingThreadView chargingThread;
    private final AppDrawerPanel drawerPanel;
    private final HomeWallpaperView homeWallpaper;
    private final FluidBackgroundView fluidBackground;
    private final HomeVeilView homeVeil;

    private int colorIndex = 0;
    private final Runnable hideGestureHintRunnable = this::fadeOutGestureHint;

    public OrbUiController(Context context,
                           OrbView orbView,
                           TextView gestureHintView,
                           Handler mainHandler,
                           InkDrawingView inkZone,
                           ChargingThreadView chargingThread,
                           AppDrawerPanel drawerPanel,
                           HomeWallpaperView homeWallpaper,
                           FluidBackgroundView fluidBackground,
                           HomeVeilView homeVeil) {
        this.context = context;
        this.orbView = orbView;
        this.gestureHintView = gestureHintView;
        this.mainHandler = mainHandler;
        this.inkZone = inkZone;
        this.chargingThread = chargingThread;
        this.drawerPanel = drawerPanel;
        this.homeWallpaper = homeWallpaper;
        this.fluidBackground = fluidBackground;
        this.homeVeil = homeVeil;
    }

    public OrbView getOrbView() {
        return orbView;
    }

    public int getColorIndex() {
        return colorIndex;
    }

    public void applyVisualPhase(PegaseVisualPhase phase) {
        if (orbView == null) return;
        PegaseVisualPhase p = phase != null ? phase : PegaseVisualPhase.IDLE;
        orbView.setListening(p.isListening());
        orbView.setThinking(p.isThinking());
    }

    public void setListening(boolean active) {
        if (orbView != null) orbView.setListening(active);
    }

    public void setThinking(boolean thinking) {
        if (orbView != null) orbView.setThinking(thinking);
    }

    public void applyWakeHealth(WakeHealthStatus status) {
        if (orbView == null) return;
        boolean problem = status != null && status.isProblem();
        orbView.setWakeHealthProblem(problem);
        if (!problem) {
            applyPersonalization();
        }
    }

    public void deployWings() {
        if (orbView != null) orbView.deployWings();
    }

    public void pauseAmbient() {
        if (orbView != null) orbView.pauseAmbient();
    }

    public void resumeAmbient() {
        if (orbView != null) orbView.resumeAmbient();
    }

    public boolean collapseIfExpanded() {
        return orbView != null && orbView.collapseIfExpanded();
    }

    public void maybeShowGestureHint() {
        if (gestureHintView == null || GestureHintsStore.isComplete(context)) return;
        GestureHintsStore.onHomeReturn(context);
        String hint = GestureHintsStore.nextHint(context);
        if (hint == null) return;
        int idx = GestureHintsStore.indexOfHint(hint);
        gestureHintView.setText(hint);
        gestureHintView.setVisibility(View.VISIBLE);
        gestureHintView.animate().cancel();
        gestureHintView.animate().alpha(0.92f).setDuration(280).start();
        GestureHintsStore.markShown(context, idx);
        mainHandler.removeCallbacks(hideGestureHintRunnable);
        mainHandler.postDelayed(hideGestureHintRunnable, 4000L);
    }

    public void fadeOutGestureHint() {
        if (gestureHintView == null) return;
        gestureHintView.animate().cancel();
        gestureHintView.animate().alpha(0f).setDuration(220).withEndAction(() -> {
            if (gestureHintView != null) gestureHintView.setVisibility(View.GONE);
        }).start();
    }

    public void hideGestureHintNow() {
        mainHandler.removeCallbacks(hideGestureHintRunnable);
        if (gestureHintView == null) return;
        gestureHintView.animate().cancel();
        gestureHintView.setAlpha(0f);
        gestureHintView.setVisibility(View.GONE);
    }

    public void refreshShortcutSlots() {
        if (orbView == null) return;
        List<OrbView.AppSlot> slots = new ArrayList<>();
        PackageManager pm = context.getPackageManager();
        int iconSize = (int) (context.getResources().getDisplayMetrics().density * 48);
        for (int i = 0; i < ShortcutStore.SLOT_COUNT; i++) {
            ShortcutStore.Slot slot = ShortcutStore.getSlot(context, i);
            Drawable icon = null;
            if (slot.isWeb()) {
                icon = webShortcutIcon(pm, iconSize);
            } else if (slot.isApp()) {
                try {
                    Drawable raw = pm.getApplicationIcon(slot.packageName);
                    icon = IconThemeHelper.resolveForPackage(
                            context, slot.packageName, raw, iconSize);
                } catch (PackageManager.NameNotFoundException e) {
                    ShortcutStore.clearSlot(context, i);
                }
            }
            slots.add(new OrbView.AppSlot(icon));
        }
        orbView.setAppSlots(slots);
    }

    /** Icône générique lien — favicon du navigateur si dispo, sinon icône système. */
    private Drawable webShortcutIcon(PackageManager pm, int iconSize) {
        String[] browsers = {
                "com.android.chrome", "com.brave.browser", "org.mozilla.firefox",
                "com.microsoft.emmx", "com.sec.android.app.sbrowser"
        };
        for (String pkg : browsers) {
            try {
                Drawable raw = pm.getApplicationIcon(pkg);
                return IconThemeHelper.resolveForPackage(context, pkg, raw, iconSize);
            } catch (Exception ignored) {
            }
        }
        try {
            return context.getDrawable(android.R.drawable.ic_menu_view);
        } catch (Exception e) {
            return null;
        }
    }

    public void applyPersonalization() {
        colorIndex = PersonalizationStore.getColorIndex(context);
        OrbThemes.Palette palette = OrbThemes.get(colorIndex);
        if (orbView != null) {
            orbView.setOrbColors(palette.core, palette.middle, palette.edge);
        }
        if (inkZone != null) inkZone.setStrokeColor(palette.core);
        if (chargingThread != null) chargingThread.setAccentColors(palette.core, palette.middle);
        if (drawerPanel != null) drawerPanel.applyAccentColor(palette.middle);
        if (homeWallpaper != null) {
            homeWallpaper.setBlurRadius(PersonalizationStore.getHomeBlur(context));
            homeWallpaper.reload();
        }
        applyFluidBackground(palette);
        refreshShortcutSlots();
    }

    public void applyFluidBackground(OrbThemes.Palette palette) {
        boolean enabled = PersonalizationStore.isFluidEnabled(context);
        if (fluidBackground != null) {
            fluidBackground.setVisibility(enabled ? View.VISIBLE : View.GONE);
            fluidBackground.setPalette(palette);
            if (enabled) fluidBackground.start();
            else fluidBackground.stop();
        }
        if (homeVeil != null && fluidBackground != null && enabled) {
            homeVeil.setAccentColor(fluidBackground.getPhase().veilAccent);
        } else if (homeVeil != null) {
            homeVeil.setAccentColor(Color.parseColor("#220B7D8F"));
        }
        FluidLockWallpaperSync.syncNow(context);
    }

    public void startFluidIfEnabled() {
        if (fluidBackground != null && PersonalizationStore.isFluidEnabled(context)
                && fluidBackground.getVisibility() == View.VISIBLE) {
            fluidBackground.start();
        }
    }

    public void stopFluid() {
        if (fluidBackground != null) fluidBackground.stop();
    }

    public void refreshFluidPhaseOnResume() {
        if (fluidBackground != null && PersonalizationStore.isFluidEnabled(context)) {
            fluidBackground.refreshPhase();
            if (homeVeil != null) {
                homeVeil.setAccentColor(fluidBackground.getPhase().veilAccent);
            }
            // sync lock wallpaper reporté (LifecycleBridge deferred) — évite contention GPU.
        }
    }

    /** Sync lock Fluid — à appeler hors chemin critique du 1er frame. */
    public void syncFluidLockWallpaperIfDue() {
        FluidLockWallpaperSync.syncIfDue(context);
    }

    public void releaseWallpaperBitmap() {
        if (homeWallpaper != null) homeWallpaper.releaseBitmap();
    }

    public void reloadWallpaperIfNeeded() {
        if (homeWallpaper != null) homeWallpaper.reloadIfNeeded();
    }
}
